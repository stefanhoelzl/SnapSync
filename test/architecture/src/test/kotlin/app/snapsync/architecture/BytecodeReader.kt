package app.snapsync.architecture

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import java.io.File
import java.util.jar.JarFile
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.test.fail

/**
 * **The JVM and Android half of [CodeBase]**: the production class files a target links, read with ASM — the reading the
 * coverage-zero gate in `build-logic` already makes of compiled classes.
 *
 * The JVM shapes Kotlin emits are read as what they mean: an `object`'s `INSTANCE` read builds it, an enum entry's static
 * read is that entry, a `$$delegate_N` field read makes a member delegated, a local or anonymous class's methods are
 * reachable from whoever builds it, and `@NotNull` on a method states a non-null answer.
 */
internal object BytecodeReader {

    fun read(roots: List<File>): List<CodeClass> {
        if (roots.isEmpty()) fail("bytecode: no class directory handed in — the build's wiring of the JVM code broke")
        val nodes = roots.flatMap(::classBytes).map { bytes ->
            ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        }
        if (nodes.isEmpty()) {
            fail(
                "bytecode: ${roots.joinToString()} held no class — the build's wiring of the JVM code broke",
            )
        }
        val byName = nodes.associateBy { it.name }
        return nodes.map { node -> readClass(node, byName) }
    }

    private fun classBytes(root: File): List<ByteArray> = when {
        root.isDirectory -> root.walk().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
            .map { it.readBytes() }.toList()
        root.isFile && root.extension == "jar" -> JarFile(root).use { jar ->
            jar.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.endsWith("module-info.class") }
                .map { e -> jar.getInputStream(e).use { it.readBytes() } }.toList()
        }
        else -> emptyList()
    }

    fun fqn(internalName: String): String = internalName.replace('/', '.').replace('$', '.')

    private fun readClass(node: ClassNode, all: Map<String, ClassNode>): CodeClass {
        val owner = fqn(node.name)
        val supers = (listOfNotNull(node.superName) + node.interfaces.orEmpty())
            .filter { it != "java/lang/Object" }.map(::fqn)
        val local = node.outerMethod != null || ANONYMOUS.containsMatchIn(node.name)
        val declared = declaredNullability(node)
        val funs = node.methods.orEmpty().map { m -> readMethod(node, m, owner, all, declared) }
        val fieldTypes = node.fields.orEmpty().associate {
            it.name to codeType(Type.getType(it.desc), nullable(it.invisibleAnnotations))
        }
        return CodeClass(
            fqn = owner,
            isInterface = node.access and Opcodes.ACC_INTERFACE != 0,
            isAbstract = node.access and Opcodes.ACC_ABSTRACT != 0,
            isEnum = node.access and Opcodes.ACC_ENUM != 0,
            supertypes = supers,
            funs = funs,
            fieldInits = fieldInits(node, owner),
            fieldTypes = fieldTypes,
            delegates = node.fields.orEmpty().filter {
                it.name.startsWith(
                    DELEGATE,
                )
            }.map { codeType(Type.getType(it.desc), false) },
            claims = claims(node.invisibleAnnotations.orEmpty() + node.visibleAnnotations.orEmpty()),
            origin = Origin(local = local),
        )
    }

    private fun readMethod(
        node: ClassNode,
        m: MethodNode,
        owner: String,
        all: Map<String, ClassNode>,
        declared: Map<String, Boolean>,
    ): CodeFun {
        val key = key(owner, m.name, m.desc)
        val returnType = Type.getReturnType(m.desc)
        // What Kotlin declared wins over what the bytecode shows: a suspend function answers `Object` there.
        val nullable = declared[m.name.removeSuffix(SUSPEND_IMPL)] ?: nullable(m.invisibleAnnotations)
        val overrides = ancestors(node, all).mapNotNull { a ->
            a.methods.orEmpty().firstOrNull { it.name == m.name && it.desc == m.desc }?.let {
                key(
                    fqn(a.name),
                    it.name,
                    it.desc,
                )
            }
        }
        val facts = facts(node, m, owner)
        return CodeFun(
            key = key,
            owner = owner,
            name = m.name,
            returnType = if (returnType.sort == Type.VOID) null else codeType(returnType, nullable),
            inherited = m.access and Opcodes.ACC_BRIDGE != 0,
            delegated = facts.fieldReads.any { it.first.name.startsWith(DELEGATE) },
            overrides = overrides,
            abstract = m.access and Opcodes.ACC_ABSTRACT != 0,
            facts = facts,
            private = m.access and Opcodes.ACC_PRIVATE != 0,
        )
    }

    private fun ancestors(node: ClassNode, all: Map<String, ClassNode>): List<ClassNode> {
        val out = mutableListOf<ClassNode>()
        val todo = ArrayDeque(listOfNotNull(node.superName) + node.interfaces.orEmpty())
        val seen = mutableSetOf<String>()
        while (todo.isNotEmpty()) {
            val next = todo.removeFirst()
            if (!seen.add(next)) continue
            val n = all[next] ?: continue
            out += n
            todo += listOfNotNull(n.superName) + n.interfaces.orEmpty()
        }
        return out
    }

    fun key(owner: String, name: String, desc: String) = "$owner.$name $desc"

    private fun facts(node: ClassNode, m: MethodNode, owner: String): Facts {
        val reader = MethodFacts(m, key(owner, m.name, m.desc), framesOf(node, m))
        m.instructions?.forEach(reader::read)
        return reader.facts()
    }

    /** The facts of one method's instructions, instruction by instruction. */
    private class MethodFacts(
        private val m: MethodNode,
        private val self: String,
        private val frames: Array<Frame<SourceValue>?>?,
    ) {
        private val builds = mutableSetOf<String>()
        private val entries = mutableSetOf<String>()
        private val calls = mutableListOf<CallRef>()
        private val params = mutableListOf<Pair<CodeType, String>>()
        private val reads = mutableListOf<Pair<FieldRef, CodeType>>()
        private val writes = mutableSetOf<FieldRef>()
        private val handsOn = mutableSetOf<String>()
        private val returns = mutableListOf<Returned>()
        private val casts = mutableListOf<Cast>()
        private val slots = paramSlots(m)

        fun facts() = Facts(
            builds = builds, enumEntries = entries, calls = calls, inflows = emptyList(), casts = casts, paramReads = params,
            fieldReads = reads, fieldWrites = writes, handsOn = handsOn, returns = returns,
        )

        fun read(insn: AbstractInsnNode) {
            when (insn) {
                is TypeInsnNode -> typeInsn(insn)
                is FieldInsnNode -> fieldInsn(insn)
                is MethodInsnNode -> calls += callOf(insn, nullable = true)
                is InvokeDynamicInsnNode -> insn.bsmArgs.orEmpty().filterIsInstance<Handle>().forEach { h ->
                    handsOn += key(fqn(h.owner), h.name, h.desc)
                }
                is VarInsnNode -> if (insn.opcode in LOADS) slots[insn.`var`]?.let { params += it to self }
                is InsnNode -> if (insn.opcode in RETURNS) returns += returned(insn)
            }
        }

        private fun typeInsn(insn: TypeInsnNode) {
            when (insn.opcode) {
                Opcodes.NEW -> builds += fqn(insn.desc)
                Opcodes.CHECKCAST -> {
                    val sources = frames?.let { StackSources.sourcesOfStackTop(m, it, insn, self, mutableSetOf()) }
                        ?: listOf(Source.Unknown("an unanalysable method"))
                    casts += Cast(codeType(Type.getObjectType(insn.desc), false), sources)
                }
            }
        }

        private fun fieldInsn(insn: FieldInsnNode) {
            val ref = FieldRef(fqn(insn.owner), insn.name)
            val type = codeType(Type.getType(insn.desc), false)
            when (insn.opcode) {
                Opcodes.GETSTATIC -> {
                    // A static field of the class's own type: an `object`'s instance, or an enum's entry.
                    val ownType = insn.desc == "L${insn.owner};"
                    if (ownType && insn.name == "INSTANCE") builds += fqn(insn.owner)
                    if (ownType && insn.name != "INSTANCE") entries += "${fqn(insn.owner)}.${insn.name}"
                    reads += ref to type
                }
                Opcodes.GETFIELD -> reads += ref to type
                Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> writes += ref
            }
        }
    }

    /** The call [insn] makes; its answer's type `null` when it answers nothing, and [nullable] as far as the JVM knows. */
    fun callOf(insn: MethodInsnNode, nullable: Boolean): CallRef {
        val ret = Type.getReturnType(insn.desc)
        return CallRef(
            key(fqn(insn.owner), insn.name, insn.desc),
            fqn(insn.owner),
            insn.name,
            if (ret.sort == Type.VOID) null else codeType(ret, nullable),
        )
    }

    private fun framesOf(node: ClassNode, m: MethodNode): Array<Frame<SourceValue>?>? =
        if (m.instructions == null || m.instructions.size() == 0) {
            null
        } else {
            try {
                Analyzer(SourceInterpreter()).analyze(node.name, m)
            } catch (_: AnalyzerException) {
                null
            }
        }

    /**
     * Whether each function and property getter of [node] answers a nullable type, as Kotlin declared it — read from
     * the class's `@Metadata`, where a suspend function keeps the answer its bytecode erases. A name declared twice
     * reads nullable if either is.
     */
    private fun declaredNullability(node: ClassNode): Map<String, Boolean> {
        val annotation = node.visibleAnnotations.orEmpty().firstOrNull { it.desc == "Lkotlin/Metadata;" } ?: return emptyMap()
        val v = annotation.values.orEmpty().chunked(2).associate { (k, x) -> k as String to x }
        val metadata = Metadata(
            kind = v["k"] as Int?,
            metadataVersion = (v["mv"] as List<*>?)?.map { it as Int }?.toIntArray(),
            data1 = (v["d1"] as List<*>?)?.map { it as String }?.toTypedArray(),
            data2 = (v["d2"] as List<*>?)?.map { it as String }?.toTypedArray(),
            extraString = v["xs"] as String?,
            packageName = v["pn"] as String?,
            extraInt = v["xi"] as Int?,
        )
        val (functions, properties) = when (
            val read = runCatching {
                KotlinClassMetadata.readLenient(
                    metadata,
                )
            }.getOrNull()
        ) {
            is KotlinClassMetadata.Class -> read.kmClass.functions to read.kmClass.properties
            is KotlinClassMetadata.FileFacade -> read.kmPackage.functions to read.kmPackage.properties
            is KotlinClassMetadata.MultiFileClassPart -> read.kmPackage.functions to read.kmPackage.properties
            else -> return emptyMap()
        }
        val out = mutableMapOf<String, Boolean>()
        functions.forEach { f -> out.merge(f.name, f.returnType.isNullable, Boolean::or) }
        properties.forEach { p ->
            val cap = p.name.replaceFirstChar { it.uppercase() }
            val getter = if (p.name.startsWith("is") && p.name.getOrNull(2)?.isUpperCase() == true) p.name else "get$cap"
            out.merge(getter, p.returnType.isNullable, Boolean::or)
        }
        return out
    }

    /** The parameter each local slot holds at entry — `this` excluded. */
    private fun paramSlots(m: MethodNode): Map<Int, CodeType> {
        var slot = if (m.access and Opcodes.ACC_STATIC != 0) 0 else 1
        return Type.getArgumentTypes(m.desc).associate { t ->
            val at = slot
            slot += t.size
            at to codeType(t, true)
        }
    }

    private fun returned(insn: InsnNode): Returned {
        if (insn.opcode == Opcodes.RETURN) return Returned.Other
        var prev: AbstractInsnNode? = insn.previous
        while (prev != null && prev.opcode < 0) prev = prev.previous
        return when {
            prev == null -> Returned.Other
            prev.opcode == Opcodes.ICONST_1 && insn.opcode == Opcodes.IRETURN -> Returned.Literal("true")
            prev.opcode == Opcodes.ICONST_0 && insn.opcode == Opcodes.IRETURN -> Returned.Literal("false")
            prev.opcode == Opcodes.ACONST_NULL -> Returned.Literal("null")
            prev is FieldInsnNode && prev.opcode in setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC) ->
                Returned.Field(FieldRef(fqn(prev.owner), prev.name))
            else -> Returned.Other
        }
    }

    /** A field assigned a literal in a constructor or static initializer: the value it is initialized with. */
    private fun fieldInits(node: ClassNode, owner: String): Map<String, Facts> {
        val out = mutableMapOf<String, Facts>()
        node.methods.orEmpty().filter { it.name == "<init>" || it.name == "<clinit>" }.forEach { m ->
            m.instructions?.forEach { insn ->
                if (insn is FieldInsnNode && insn.opcode in setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC) && fqn(insn.owner) == owner) {
                    var prev: AbstractInsnNode? = insn.previous
                    while (prev != null && prev.opcode < 0) prev = prev.previous
                    val literal = when (prev?.opcode) {
                        Opcodes.ICONST_1 -> Returned.Literal("true")
                        Opcodes.ICONST_0 -> Returned.Literal("false")
                        Opcodes.ACONST_NULL -> Returned.Literal("null")
                        else -> Returned.Other
                    }
                    // Assigned twice reads as anything: only a field one literal initializes keeps it.
                    out[insn.name] = Facts(returns = listOf(if (insn.name in out) Returned.Other else literal))
                }
            }
        }
        return out
    }

    private fun codeType(t: Type, nullable: Boolean): CodeType = when (t.sort) {
        Type.OBJECT -> CodeType(fqn(t.internalName), nullable)
        Type.ARRAY -> CodeType("kotlin.Array", nullable)
        else -> CodeType(t.className, false)
    }

    /** Kotlin marks every reference it declares non-null `@NotNull`; anything else may be null. */
    private fun nullable(annotations: List<AnnotationNode>?): Boolean =
        annotations.orEmpty().none { it.desc == "Lorg/jetbrains/annotations/NotNull;" }

    private fun claims(annotations: List<AnnotationNode>): List<Triple<String, String, String>> =
        annotations.filter { it.desc == "Lapp/snapsync/model/NeverAnswers;" }.flatMap { a ->
            val values = a.values.orEmpty().chunked(2).associate { (k, v) -> k as String to v }
            (values["claims"] as? List<*>).orEmpty().filterIsInstance<AnnotationNode>().map { n ->
                val v = n.values.orEmpty().chunked(2).associate { (k, x) -> k as String to x as String }
                Triple(v["member"].orEmpty(), v["answer"].orEmpty(), v["handler"].orEmpty())
            }
        }

    private const val SUSPEND_IMPL = "\$suspendImpl"
    val DUPS = setOf(
        Opcodes.DUP,
        Opcodes.DUP_X1,
        Opcodes.DUP_X2,
        Opcodes.DUP2,
        Opcodes.DUP2_X1,
        Opcodes.DUP2_X2,
    )
    private const val DELEGATE = "\$\$delegate_"
    private val ANONYMOUS = Regex("[$]\\d+$|[$]lambda")
    val LOADS = setOf(Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD)
    private val RETURNS = setOf(
        Opcodes.IRETURN,
        Opcodes.ARETURN,
        Opcodes.LRETURN,
        Opcodes.FRETURN,
        Opcodes.DRETURN,
        Opcodes.RETURN,
    )
}

/** Where a value on the JVM operand stack came from: followed back through locals, copies and casts. */
private object StackSources {
    /** Where the value on top of the stack at [at] came from, following it back through locals and casts. */
    fun sourcesOfStackTop(
        m: MethodNode,
        frames: Array<Frame<SourceValue>?>,
        at: AbstractInsnNode,
        self: String,
        seen: MutableSet<AbstractInsnNode>,
    ): List<Source> {
        val frame = frames[m.instructions.indexOf(at)] ?: return listOf(Source.Unknown("unreachable code"))
        return frame.getStack(frame.stackSize - 1).insns.flatMap { sourceOf(m, frames, it, self, seen) }
    }

    fun sourceOf(
        m: MethodNode,
        frames: Array<Frame<SourceValue>?>,
        insn: AbstractInsnNode,
        self: String,
        seen: MutableSet<AbstractInsnNode>,
    ): List<Source> {
        if (!seen.add(insn)) return emptyList()
        return when {
            insn is VarInsnNode && insn.opcode in BytecodeReader.LOADS -> loadSources(m, frames, insn, self, seen)
            insn is MethodInsnNode -> listOf(Source.Call(BytecodeReader.callOf(insn, nullable = true)))
            // A copy or a cast is the value it copies: the one on top of the stack where it ran.
            insn.opcode in BytecodeReader.DUPS || insn is TypeInsnNode && insn.opcode == Opcodes.CHECKCAST ->
                sourcesOfStackTop(m, frames, insn, self, seen)
            // A swap may be either of two.
            insn.opcode == Opcodes.SWAP -> swapSources(m, frames, insn, self, seen)
            insn is FieldInsnNode -> listOf(fieldSource(insn))
            builtHere(insn) -> listOf(Source.Ours)
            else -> listOf(Source.Unknown("a value from ${insn.javaClass.simpleName}(${insn.opcode})"))
        }
    }

    /** A value made by the method itself: a new object, a constant, a lambda. */
    fun builtHere(insn: AbstractInsnNode) =
        insn is TypeInsnNode && insn.opcode == Opcodes.NEW || insn is LdcInsnNode || insn.opcode == Opcodes.ACONST_NULL ||
            insn is InvokeDynamicInsnNode

    /** A local's value: what was stored into it, the continuation's result, or a parameter. */
    fun loadSources(
        m: MethodNode,
        frames: Array<Frame<SourceValue>?>,
        insn: VarInsnNode,
        self: String,
        seen: MutableSet<AbstractInsnNode>,
    ): List<Source> {
        val frame = frames[m.instructions.indexOf(insn)] ?: return listOf(Source.Unknown("unreachable code"))
        val local = frame.getLocal(insn.`var`)
        return when {
            local.insns.isNotEmpty() -> local.insns.flatMap { store -> sourcesOfStackTop(m, frames, store, self, seen) }
            m.name == "invokeSuspend" && insn.`var` == 1 -> listOf(Source.SuspendResult)
            else -> listOf(Source.Param(self))
        }
    }

    fun swapSources(
        m: MethodNode,
        frames: Array<Frame<SourceValue>?>,
        insn: AbstractInsnNode,
        self: String,
        seen: MutableSet<AbstractInsnNode>,
    ): List<Source> {
        val frame = frames[m.instructions.indexOf(insn)] ?: return listOf(Source.Unknown("unreachable code"))
        return (1..2).flatMap { d ->
            frame.getStack(
                frame.stackSize - d,
            ).insns.flatMap { sourceOf(m, frames, it, self, seen) }
        }
    }

    fun fieldSource(insn: FieldInsnNode): Source = when {
        insn.name == "INSTANCE" || insn.desc == "L${insn.owner};" -> Source.Ours
        // A suspend function's state machine keeps what the suspension resumed with on its continuation.
        insn.name == "result" && '$' in insn.owner -> Source.SuspendResult
        else -> Source.Field(FieldRef(BytecodeReader.fqn(insn.owner), insn.name))
    }
}

package app.snapsync.architecture

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * **The iOS half of [CodeBase]**: each production klib's IR, as `klib dump-ir` prints it, reduced to [CodeClass]es.
 *
 * Kotlin documents the dump as a debugging aid, not a stable format, so the reader FAILS CLOSED: a klib whose dump
 * yields no class, a class line it cannot read, or a tool that is missing or fails stops the gate with the reason —
 * never a quieter, emptier code base, which would accept claims it never checked. The shapes it reads were measured on
 * Kotlin 2.4.20 (`CLASS CLASS name:IosPreferences signature:… superTypes:[app.snapsync.ports.Preferences]`, a
 * `CONSTRUCTOR_CALL '…WriteOutcome.Failed.<init>…'` in a body); a Kotlin bump that moves them fails here, as
 * [PlatformVocabularyPinTest] fails on one that moves Apple's vocabulary.
 *
 * A function's facts are every line of its subtree — its lambdas and the anonymous objects it builds included — so
 * the reading can only widen what a function does, never narrow it.
 */
internal object IrReader {

    fun read(klibs: List<File>): List<CodeClass> {
        if (klibs.isEmpty()) fail("dump-ir: no klib handed in — the build's wiring of the iOS code broke")
        return klibs.flatMap { klib ->
            val classes = parse(dump(klib))
            if (classes.isEmpty()) fail("dump-ir: `$klib` yielded no class — the dump's format moved; re-read IrReader")
            classes
        }
    }

    private fun dump(klib: File): String {
        val tool = File(KonanDistribution.home(), "bin/klib")
        if (!tool.canExecute()) fail("dump-ir: no executable klib tool at $tool")
        val out = File.createTempFile("dump-ir", ".txt").apply { deleteOnExit() }
        val process = ProcessBuilder(tool.absolutePath, "dump-ir", klib.absolutePath)
            .redirectErrorStream(true).redirectOutput(out).start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("dump-ir: `klib dump-ir $klib` did not finish within 5 minutes")
        }
        val text = out.readText()
        if (process.exitValue() != 0) {
            fail(
                "dump-ir: `klib dump-ir $klib` failed (${process.exitValue()}):\n${text.take(2000)}",
            )
        }
        return text
    }

    /** One line of the dump: its indentation and its text. */
    private class Line(val depth: Int, val text: String)

    fun parse(dump: String): List<CodeClass> {
        val lines = dump.lineSequence().filter { it.isNotBlank() }.map { raw ->
            val trimmed = raw.trimStart()
            Line(raw.length - trimmed.length, trimmed)
        }.toList()
        val out = mutableListOf<CodeClass>()
        var sourceSet = ""
        lines.forEachIndexed { i, line ->
            if (line.text.startsWith("FILE ")) {
                sourceSet = SOURCE_SET.find(line.text)?.groupValues?.get(1).orEmpty()
                out += readClass(lines, i, sourceSet)
            }
            if (line.text.startsWith("CLASS ")) out += readClass(lines, i, sourceSet)
        }
        return out
    }

    /** The end (exclusive) of the subtree opened at [start]: every following line indented deeper. */
    private fun subtreeEnd(lines: List<Line>, start: Int): Int {
        var end = start + 1
        while (end < lines.size && lines[end].depth > lines[start].depth) end++
        return end
    }

    /**
     * A class, or a FILE's top-level declarations as a class of their own (`<package>.<file:Name.kt>`) — the functions
     * an `expect` declaration's `actual`s are, and the helpers adapters call.
     */
    private fun readClass(lines: List<Line>, start: Int, sourceSet: String): CodeClass {
        val head = lines[start].text
        val file = FILE_HEAD.find(head)
        val m = if (file == null) CLASS_HEAD.find(head) ?: fail("dump-ir: cannot read the class line `$head`") else null
        val kind = m?.groupValues?.get(1) ?: "FILE"
        val fqn = if (file != null) {
            // The source set is part of the name: an `expect` file and its `actual` share the file's name.
            "${file.groupValues[1]}.<file:$sourceSet/${file.groupValues[2].substringAfterLast('/')}>"
        } else {
            fqnOfSignature(m!!.groupValues[3])
                ?: m.groupValues[2] // a local class has no signature; its name stands in, and nothing calls it by key
        }
        val supertypes = SUPERTYPES.find(head)?.groupValues?.get(1).orEmpty()
            .split(';').map { stripType(it.trim()) }.filter { it.isNotEmpty() && it != "kotlin.Any" }
        val end = subtreeEnd(lines, start)
        val members = Members(lines, fqn)
        val memberDepth = (start + 1 until end).firstOrNull()?.let { lines[it].depth } ?: (lines[start].depth + 2)
        var i = start + 1
        while (i < end) {
            if (lines[i].depth != memberDepth) {
                i++
                continue
            }
            val stop = subtreeEnd(lines, i)
            members.read(i, stop)
            i = stop
        }
        return CodeClass(
            fqn = fqn,
            isInterface = kind == "INTERFACE",
            isAbstract = "modality:ABSTRACT" in head || "modality:SEALED" in head,
            isEnum = kind == "ENUM_CLASS",
            supertypes = supertypes,
            funs = members.funs,
            fieldInits = members.fieldInits,
            fieldTypes = members.fieldTypes,
            delegates = members.delegates,
            claims = members.claims,
            origin = Origin(local = m != null && m.groupValues[3].isEmpty(), sourceSet = sourceSet),
        )
    }

    /** A class's members as its subtree lists them: functions, fields, their initializers and the class's claims. */
    private class Members(private val lines: List<Line>, private val fqn: String) {
        val funs = mutableListOf<CodeFun>()
        val fieldInits = mutableMapOf<String, Facts>()
        val fieldTypes = mutableMapOf<String, CodeType>()
        val delegates = mutableListOf<CodeType>()
        val claims = mutableListOf<Triple<String, String, String>>()

        /** The member whose subtree is lines [i] until [stop]. */
        fun read(i: Int, stop: Int) {
            val text = lines[i].text
            when {
                text == "annotations:" -> (i + 1 until stop).forEach { claimsIn(lines[it].text) }
                text.startsWith("FUN ") || text.startsWith("CONSTRUCTOR ") -> funs += readFun(lines, i, stop, fqn)
                text.startsWith("PROPERTY ") -> property(i, stop)
                text.startsWith("FIELD ") -> field(i)
                text.startsWith(
                    "ANONYMOUS_INITIALIZER",
                ) -> fieldInits["<init-block-$i>"] = facts(lines, i + 1, stop, fqn, "<init>")
            }
        }

        private fun claimsIn(text: String) = NEVER.findAll(text).forEach { n ->
            claims += Triple(unquote(n.groupValues[1]), unquote(n.groupValues[2]), unquote(n.groupValues[3]))
        }

        /** A property: its accessors, and its backing field with the field's type and initializer. */
        private fun property(
            i: Int,
            stop: Int,
        ) = (i + 1 until stop).filter { lines[it].depth == lines[i].depth + 2 }.forEach { j ->
            val t = lines[j].text
            if (t.startsWith("FUN ")) funs += readFun(lines, j, subtreeEnd(lines, j), fqn)
            if (t.startsWith("FIELD ")) {
                val name = FIELD_NAME.find(t)?.groupValues?.get(1) ?: return@forEach
                FIELD_TYPE.find(t)?.groupValues?.get(1)?.let { fieldTypes[name] = codeType(it) }
                fieldInits[name] = initializer(lines, j, fqn, name)
            }
        }

        /** A field outside a property: a delegate's, or one the compiler made. */
        private fun field(i: Int) {
            val name = FIELD_NAME.find(lines[i].text)?.groupValues?.get(1) ?: return
            val type = FIELD_TYPE.find(lines[i].text)?.groupValues?.get(1)?.let(::codeType) ?: return
            fieldTypes[name] = type
            if (name.startsWith("\$\$delegate_")) delegates += type
            fieldInits[name] = initializer(lines, i, fqn, name)
        }
    }

    private fun readFun(lines: List<Line>, start: Int, end: Int, owner: String): CodeFun {
        val head = lines[start].text
        val name = FUN_NAME.find(head)?.groupValues?.get(1) ?: if (head.startsWith("CONSTRUCTOR")) {
            "<init>"
        } else {
            fail("dump-ir: cannot read the function line `$head`")
        }
        val signature = SIGNATURE.find(head)?.groupValues?.get(1)
        val key = signature ?: "$owner#$name#${lines[start].depth}:$start"
        val overrides = mutableListOf<String>()
        var j = start + 1
        while (j < end) {
            if (lines[j].text == "overridden:") {
                val stop = subtreeEnd(lines, j)
                (j + 1 until stop).forEach { k -> overrides += lines[k].text }
                break
            }
            j++
        }
        val abstract = "modality:ABSTRACT" in head && (start + 1 until end).none { lines[it].text.startsWith("BLOCK_BODY") }
        return CodeFun(
            key = key,
            owner = owner,
            name = name,
            returnType = RETURN_TYPE.find(head)?.groupValues?.get(1)?.let(::codeType),
            inherited = head.startsWith("FUN FAKE_OVERRIDE"),
            delegated = head.startsWith("FUN DELEGATED_MEMBER"),
            overrides = overrides,
            abstract = abstract,
            facts = facts(lines, start + 1, end, owner, name, signature),
            private = "visibility:private" in head,
        )
    }

    /** The facts of lines [from, to): a function's subtree, or a field's initializer. */
    private fun facts(
        lines: List<Line>,
        from: Int,
        to: Int,
        owner: String,
        name: String,
        signature: String? = null,
    ): Facts {
        val reader = FactsReader(lines, name, signature)
        for (k in from until to) reader.read(k)
        return reader.facts()
    }

    /** The facts of one function's lines, node by node. */
    private class FactsReader(private val lines: List<Line>, private val name: String, private val signature: String?) {
        private val builds = mutableSetOf<String>()
        private val entries = mutableSetOf<String>()
        private val calls = mutableListOf<CallRef>()
        private val casts = mutableListOf<Cast>()
        private val params = mutableListOf<Pair<CodeType, String>>()
        private val reads = mutableListOf<Pair<FieldRef, CodeType>>()
        private val writes = mutableSetOf<FieldRef>()
        private val handsOn = mutableSetOf<String>()
        private val returns = mutableListOf<Returned>()

        fun facts() = Facts(
            builds = builds, enumEntries = entries, calls = calls, inflows = emptyList(), casts = casts, paramReads = params,
            fieldReads = reads, fieldWrites = writes, handsOn = handsOn, returns = returns,
        )

        /** Line [k], if it is a node a fact is read from. */
        fun read(k: Int) {
            val node = NODE.find(lines[k].text)?.value ?: return
            val text = lines[k].text.substring(lines[k].text.indexOf(node))
            when {
                node.startsWith("CONSTRUCTOR_CALL") || node.startsWith("DELEGATING_CONSTRUCTOR_CALL") -> constructorCall(
                    node,
                    text,
                )
                node.startsWith("GET_OBJECT") -> quotedFqn(text)?.let { builds += it }
                node.startsWith("GET_ENUM") -> quotedFqn(text)?.let { entries += it }
                node.startsWith("CALL") -> calls += call(text)
                node.startsWith("TYPE_OP") -> typeOp(k, text)
                node.startsWith("GET_VAR") -> getVar(text)
                node.startsWith("GET_FIELD") -> fieldOf(text)?.let { (ref, type) -> reads += ref to type }
                node.startsWith("SET_FIELD") -> fieldOf(text)?.let { (ref, _) -> writes += ref }
                node.startsWith("RICH_FUNCTION_REFERENCE") || node.startsWith("FUNCTION_REFERENCE") ->
                    REFLECTION_TARGET.find(text)?.groupValues?.get(1)?.takeIf { it != "null" }?.let { handsOn += it }
                node.startsWith("RETURN") && returnsFrom(text, signature, name) ->
                    returns += returned(lines.getOrNull(k + 1)?.text.orEmpty())
            }
        }

        private fun quotedFqn(text: String) = QUOTED.find(text)?.groupValues?.get(1)?.let(::fqnOfSignature)

        private fun constructorCall(node: String, text: String) {
            val target = QUOTED.find(text)?.groupValues?.get(1).orEmpty()
            fqnOfSignature(target.substringBefore(".<init>"))?.let {
                if (node.startsWith("CONSTRUCTOR_CALL")) builds += it
                calls += CallRef(target, it, "<init>", null)
            }
        }

        private fun call(text: String): CallRef {
            val target = QUOTED.find(text)?.groupValues?.get(1).orEmpty()
            val type = TYPE.find(text)?.groupValues?.get(1)?.let(::codeType)
            if (target.contains('|')) {
                val path = target.substringBefore('|')
                return CallRef(
                    target,
                    fqnOfSignature(path.substringBeforeLast('.')),
                    path.substringAfterLast('.'),
                    type,
                )
            }
            // A private callee is printed by its declaration, `… fun NAME (…): T declared in OWNER`.
            val m = PRIVATE_CALL.find(target)
            return CallRef(null, m?.groupValues?.get(2), m?.groupValues?.get(1) ?: target, type)
        }

        private fun typeOp(k: Int, text: String) {
            if (ORIGIN.find(text)?.groupValues?.get(1).orEmpty() !in CASTS) return
            val to = TYPE.find(text)?.groupValues?.get(1)?.let(::codeType) ?: return
            // The operand is the node's first child, one level in, with its own static type.
            val from = lines.getOrNull(k + 1)?.takeIf { it.depth > lines[k].depth }
                ?.let { TYPE.find(it.text)?.groupValues?.get(1) }?.let(::codeType)
            casts += Cast(to, listOf(from?.let { Source.Typed(it) } ?: Source.Unknown("an operand the dump did not type")))
        }

        /** A read of a parameter: a variable declared by none of `val`, `var` or a compiler temporary. */
        private fun getVar(text: String) {
            val decl = QUOTED.find(text)?.groupValues?.get(1).orEmpty()
            val m = VAR_DECL.find(decl) ?: return
            val local = decl.startsWith("val ") || decl.startsWith("var ") || decl.startsWith("<")
            if (!local) params += codeType(m.groupValues[1]) to m.groupValues[2]
        }
    }

    /**
     * A field's initializer, as facts whose one [Facts.returns] is the expression it is initialized with — what a
     * property's getter answers when it returns the field.
     */
    private fun initializer(lines: List<Line>, field: Int, owner: String, name: String): Facts {
        val end = subtreeEnd(lines, field)
        val body = facts(lines, field + 1, end, owner, "<init-$name>")
        val expression = (field + 1 until end).firstOrNull {
            lines[it].text == "EXPRESSION_BODY"
        }?.let { lines.getOrNull(it + 1) }
        return Facts(
            builds = body.builds, enumEntries = body.enumEntries, calls = body.calls, inflows = body.inflows, casts = body.casts,
            paramReads = body.paramReads, fieldReads = body.fieldReads, fieldWrites = body.fieldWrites, handsOn = body.handsOn,
            returns = listOfNotNull(expression?.let { returned(it.text) }),
        )
    }

    private fun returnsFrom(text: String, signature: String?, name: String): Boolean {
        val from = FROM.find(text)?.groupValues?.get(1) ?: return false
        return from == signature || from.contains("fun $name ")
    }

    private fun returned(expression: String): Returned {
        val e = expression.substringAfter(": ", expression).trim().let {
            if (it.startsWith("ARG")) it.substringAfter(": ") else it
        }
        return when {
            e.startsWith("CONST Boolean") -> Returned.Literal(VALUE.find(e)?.groupValues?.get(1).orEmpty())
            e.startsWith("CONST Null") -> Returned.Literal("null")
            e.startsWith("GET_FIELD") -> fieldOf(e)?.let { Returned.Field(it.first) } ?: Returned.Other
            else -> Returned.Other
        }
    }

    private fun fieldOf(text: String): Pair<FieldRef, CodeType>? {
        val sig = FIELD_SIGNATURE.find(text)?.groupValues?.get(1)
        val name = FIELD_NAME.find(text)?.groupValues?.get(1) ?: return null
        val type = FIELD_TYPE.find(text)?.groupValues?.get(1)?.let(::codeType) ?: return null
        val owner = sig?.let { fqnOfSignature(it.substringBefore('|').substringBeforeLast('.')) } ?: return null
        return FieldRef(owner, name) to type
    }

    /** `app.snapsync.model/FileResult.Ok|null[0]` → `app.snapsync.model.FileResult.Ok`. */
    fun fqnOfSignature(signature: String): String? {
        val path = signature.substringBefore('|')
        if ('/' !in path) return null
        val (pkg, rest) = path.split('/', limit = 2)
        return if (pkg.isEmpty()) rest else "$pkg.$rest"
    }

    fun codeType(text: String): CodeType {
        val t = text.trim()
        val nullable = t.endsWith("?")
        val name = stripType(t.removeSuffix("?"))
        // A type parameter prints as `T of app.snapsync.…Owner.fun`, its name alone where the dump cuts at the space: every
        // class has a dotted name, so an undotted one is a type parameter — and could be any type.
        if (" of " in t.substringBefore('<') || '.' !in name) {
            return CodeType(
                NeverAnswersCheck.TYPE_PARAMETER,
                nullable,
            )
        }
        return CodeType(name, nullable)
    }

    /** A type's class: generic arguments, nullability and annotations dropped. */
    private fun stripType(text: String): String = text.substringBefore('<').removeSuffix("?").substringAfterLast(' ')

    private fun unquote(v: String) = if (v == "<null>") "" else v.removeSurrounding("\"")

    private val CASTS = setOf("CAST", "SAFE_CAST", "IMPLICIT_CAST", "IMPLICIT_NOTNULL")

    // A plugin's class carries its origin first: `CLASS GENERATED[…SerializationPluginKey] OBJECT name:Companion …`.
    private val CLASS_HEAD = Regex(
        """^CLASS (?:\S+ )*?(CLASS|INTERFACE|OBJECT|ENUM_CLASS|ENUM_ENTRY|ANNOTATION_CLASS) name:(\S+)(?: signature:(\S+))?""",
    )
    private val FILE_HEAD = Regex("""^FILE fqName:(\S*) fileName:(\S+)""")
    private val SOURCE_SET = Regex("""/src/(\w+)/""")
    private val SUPERTYPES = Regex("""superTypes:\[(.*)]$""")
    private val FUN_NAME = Regex("""name:(\S+)""")
    private val SIGNATURE = Regex("""signature:(\S+)""")
    private val RETURN_TYPE = Regex("""returnType:(\S+)""")
    private val NODE = Regex(
        """\b(CONSTRUCTOR_CALL|DELEGATING_CONSTRUCTOR_CALL|GET_OBJECT|GET_ENUM|CALL|TYPE_OP|GET_VAR|GET_FIELD|SET_FIELD|""" +
            """RICH_FUNCTION_REFERENCE|FUNCTION_REFERENCE|RETURN)\b""",
    )
    private val QUOTED = Regex("""'([^']*)'""")
    private val TYPE = Regex("""\btype=(\S+)""")
    private val ORIGIN = Regex("""\borigin=(\w+)""")
    private val FROM = Regex("""from='([^']*)'""")
    private val VALUE = Regex("""value=(\S+)""")
    private val PRIVATE_CALL = Regex("""fun (\S+) \(.*declared in (\S+)$""")
    private val VAR_DECL = Regex("""^[^:]+: (\S+) declared in (\S+)$""")
    private val REFLECTION_TARGET = Regex("""reflectionTarget='([^']*)'""")
    private val FIELD_NAME = Regex("""FIELD \w+ name:(\S+)""")
    private val FIELD_SIGNATURE = Regex("""signature:\[ (\S+)""")
    private val FIELD_TYPE = Regex("""FIELD \w+ name:\S+.*? type:(\S+)""")
    private val NEVER = Regex("""Never\(1 = (<null>|"[^"]*"), 2 = (<null>|"[^"]*"), 3 = (<null>|"[^"]*")\)""")
}

/** The Kotlin/Native distribution the build compiled against: where `klib` lives (`PlatformVocabularyPinTest`). */
internal object KonanDistribution {
    fun home(): File {
        val version = File(SourceScan.repoRoot, "gradle/libs.versions.toml").readLines()
            .first { it.startsWith("kotlin = ") }.substringAfter('"').substringBefore('"')
        val root = System.getenv("KONAN_DATA_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".konan")
        return root.listFiles().orEmpty().firstOrNull { f ->
            f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith("-$version")
        } ?: fail("no Kotlin/Native $version distribution under $root — compile an iOS target first")
    }
}

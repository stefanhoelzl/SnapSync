package app.snapsync.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The ZERO coverage gate's judgement (`docs/architecture.md`, "Coverage"), pure so it is tested without Gradle: given
 * a module's Kover `report.xml` and its main sources, every INSTRUCTION and BRANCH the report counts as missed fails the
 * module — except COMPILER-GENERATED GLUE, recognised by its shape. Each shape is code a compiler emits that no input
 * can reach; nothing a person wrote is ever excused. The shapes:
 *
 * - **Compose declaration glue**: the declaration lines of a `@Composable` function, from `fun` through the body's
 *   opening `{` (or an expression body's `=`). The Compose compiler attributes its glue there — restart groups, the
 *   `$changed`/`$default` bits, `skipToGroupEnd`, `updateScope` — and no test can reach all of it. A body line is never
 *   excused, and composable LAMBDAS carry no excusable glue (measured 2026-10-07: every miss inside one of `:ui:*`'s 67
 *   is a real branch).
 * - **kotlinx-serialization's zero-mask missing-field check** ([serializationGlue]): in a `@Serializable` class whose
 *   every field has a default, the plugin's deserialization constructor still opens with `if ((0 & seen) != 0)
 *   throwMissingFieldException(…)`. The mask is the constant 0, so the throw cannot happen. Recognised in the BYTECODE,
 *   and only those instructions and that one branch are excused: the rest of the class's header line is judged as
 *   ever, and a class with a required field (a mask other than 0) is never excused — its check is real and a test
 *   reaches it by leaving the field out.
 *
 * The declaration ranges come from a source scan, and the scan is CHECKED against the bytecode: every composable
 * method the report lists (a `Composer` parameter in its descriptor, not a lambda) must match a scanned declaration of
 * the same name in the same file, and the reverse. A scanner that misreads a signature therefore fails the build instead
 * of excusing — or refusing — the wrong lines.
 */
object CoverageZero {

    /**
     * One module's verdict: the misses that fail it, the Compose declaration glue excused, every other glue site
     * excused (each named, so the gate can print it), and any scan/bytecode disagreement.
     */
    data class Verdict(
        val misses: List<Miss>,
        val excused: Tally,
        val mismatches: List<String>,
        val excusedGlue: List<Glue> = emptyList(),
    ) {
        val passes: Boolean get() = misses.isEmpty() && mismatches.isEmpty()
    }

    data class Miss(val file: String, val line: Int, val instructions: Int, val branches: Int, val text: String)

    data class Tally(val lines: Int, val instructions: Int, val branches: Int)

    /**
     * A glue site the BYTECODE shows: [instructions] and [branches] on [line] of [file] (`<package path>/<file name>`,
     * as the report names it) that no input reaches, in [site] (`<class>.<method>`).
     */
    data class Glue(val file: String, val line: Int, val instructions: Int, val branches: Int, val site: String)

    /** A scanned `@Composable` declaration: the function's simple name and its declaration lines (1-based). */
    data class Declaration(val name: String, val lines: IntRange)

    private const val COMPOSER = "Landroidx/compose/runtime/Composer;"

    /**
     * Judge [report] (Kover's XML, JaCoCo-shaped) against [sources]: the module's main Kotlin files, keyed by
     * `<package path>/<file name>` exactly as the report names a source file. A key with several files is ambiguous
     * and excuses nothing.
     */
    fun judge(report: String, sources: Map<String, List<List<String>>>, glue: List<Glue> = emptyList()): Verdict {
        val root = parse(report)
        val misses = mutableListOf<Miss>()
        val mismatches = mutableListOf<String>()
        var excused = Tally(0, 0, 0)
        val excusedGlue = mutableListOf<Glue>()
        val glueAt = glue.associateBy { it.file to it.line }
        for (pkg in root.children("package")) {
            val pkgPath = pkg.getAttribute("name")
            val composables = composableMethods(pkg)
            for (sf in pkg.children("sourcefile")) {
                val key = "$pkgPath/${sf.getAttribute("name")}"
                val candidates = sources[key].orEmpty()
                val lines = candidates.singleOrNull()
                val declarations = lines?.let(::declarations).orEmpty()
                val expected = composables[sf.getAttribute("name")].orEmpty()
                val scanned = declarations.map { it.name }.toSet()
                if (lines == null && expected.isNotEmpty()) {
                    mismatches += "$key: ${candidates.size} source files match, so its composables cannot be located"
                } else if (expected != scanned) {
                    mismatches += "$key: the bytecode's composables ${expected.sorted()} != the scanned ${scanned.sorted()}"
                }
                val declarationLines = declarations.flatMap { it.lines }.toSet()
                for (line in sf.children("line")) {
                    val nr = line.int("nr")
                    var mi = line.int("mi")
                    var mb = line.int("mb")
                    if (mi == 0 && mb == 0) continue
                    val site = glueAt[key to nr]
                    if (site != null) {
                        if (mi < site.instructions || mb < site.branches) {
                            // The report counts less than the bytecode's unreachable block: it is not measuring what
                            // this reader recognised, so the glue cannot be told apart from the line's own code.
                            mismatches += "$key:$nr: ${site.site} holds ${site.instructions} instr / ${site.branches} " +
                                "branch of unreachable glue, but the line misses only $mi / $mb"
                        } else {
                            excusedGlue += site
                            mi -= site.instructions
                            mb -= site.branches
                            if (mi == 0 && mb == 0) continue
                        }
                    }
                    if (nr in declarationLines) {
                        excused = Tally(excused.lines + 1, excused.instructions + mi, excused.branches + mb)
                    } else {
                        misses += Miss(key, nr, mi, mb, lines?.getOrNull(nr - 1)?.trim().orEmpty())
                    }
                }
            }
        }
        mismatches += shapeProblems(root)
        return Verdict(misses, excused, mismatches, excusedGlue)
    }

    private const val MARKER = "Lkotlinx/serialization/internal/SerializationConstructorMarker;)V"
    private const val PLUGIN_EXCEPTIONS = "kotlinx/serialization/internal/PluginExceptionsKt"

    /**
     * Every zero-mask missing-field check in [classFiles] (the module's compiled classes): a constructor ending in a
     * `SerializationConstructorMarker` whose code OPENS with `iconst_0; iload_1; iand; ifeq` — the plugin's
     * `(0 & seen) != 0` — and whose skipped block ends in `PluginExceptionsKt.throwMissingFieldException`. The site
     * excuses exactly that block's instructions and the one branch, on the line the block is attributed to.
     *
     * Anything else is not this shape and is not excused: a non-zero mask (`iconst_1`, `bipush`, …), a check on another
     * slot, a block that does not end in the plugin's throw.
     */
    fun serializationGlue(classFiles: Iterable<ByteArray>): List<Glue> = classFiles.flatMap { bytes ->
        val cls = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        val file = cls.sourceFile ?: return@flatMap emptyList()
        val pkg = cls.name.substringBeforeLast('/', "")
        val key = if (pkg.isEmpty()) file else "$pkg/$file"
        cls.methods.filter { it.name == "<init>" && it.desc.endsWith(MARKER) }.mapNotNull { method ->
            zeroMaskCheck(method.instructions.toArray())?.let { (line, instructions) ->
                Glue(key, line, instructions, branches = 1, site = "${cls.name.replace('/', '.')}.<init>")
            }
        }
    }

    /** The opening `(0 & seen) != 0` check in [code]: its line and its skipped block's instruction count, or null. */
    private fun zeroMaskCheck(code: Array<AbstractInsnNode>): Pair<Int, Int>? {
        val real = code.filter { it.opcode >= 0 }
        val (mask, seen, and, jump) = real.take(4).takeIf { it.size == 4 } ?: return null
        val opens = mask is InsnNode && mask.opcode == Opcodes.ICONST_0 &&
            seen is VarInsnNode && seen.opcode == Opcodes.ILOAD && seen.`var` == 1 &&
            and.opcode == Opcodes.IAND && jump is JumpInsnNode && jump.opcode == Opcodes.IFEQ
        if (!opens) return null
        jump as JumpInsnNode
        val from = code.indexOf(jump) + 1
        val to = code.indexOf(jump.label)
        if (to < from) return null
        val block = code.slice(from until to).filter { it.opcode >= 0 }
        val throws = block.lastOrNull()?.let {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESTATIC && it.owner == PLUGIN_EXCEPTIONS &&
                it.name == "throwMissingFieldException"
        } == true
        if (!throws) return null
        val line = code.take(from).filterIsInstance<LineNumberNode>().lastOrNull()?.line ?: return null
        return line to block.size
    }

    /**
     * The gate reads per-LINE counts, so it guards the report shape it relies on: a report with no package, or whose
     * line counts do not add up to its own totals (measured equal on every module, 2026-10-07), would otherwise pass
     * vacuously after a Kover upgrade moved the counts somewhere this reader does not look.
     */
    private fun shapeProblems(root: Element): List<String> {
        val lines = root.children("package").flatMap { it.children("sourcefile") }.flatMap { it.children("line") }
        if (lines.isEmpty()) return listOf("the report holds no package line counts — has Kover's XML shape changed?")
        val totals = root.children("counter").associate { it.getAttribute("type") to it.int("missed") + it.int("covered") }
        return listOf("INSTRUCTION" to listOf("mi", "ci"), "BRANCH" to listOf("mb", "cb")).mapNotNull { (type, attrs) ->
            val summed = lines.sumOf { line -> attrs.sumOf { line.int(it) } }
            "the report's $type total ${totals[type]} != its lines' $summed — has Kover's XML shape changed?"
                .takeIf { totals[type] != summed }
        }
    }

    private fun parse(report: String): Element = DocumentBuilderFactory.newInstance().apply {
        // The report names Kover's DTD; reading it would reach the network.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(report.byteInputStream()).documentElement

    /** Per source file name, the simple names of the composable FUNCTIONS the bytecode holds (lambdas excluded). */
    private fun composableMethods(pkg: Element): Map<String, Set<String>> =
        pkg.children("class")
            .flatMap { cls ->
                cls.children("method")
                    .filter { COMPOSER in it.getAttribute("desc") && "\$lambda" !in it.getAttribute("name") }
                    // Mangled names: an internal member's is `name$module`, a function taking a value class (`Dp`,
                    // `Color`) is `name-<hash>`. Neither character can appear in an unquoted Kotlin name.
                    .map { cls.getAttribute("sourcefilename") to it.getAttribute("name").substringBefore('$').substringBefore('-') }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private val ANNOTATION = Regex("""^\s*@Composable\b(?!\s*\()""")
    private val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,\s]+\.)?`?(\w+)`?\s*[(<]""")
    private const val LOOKAHEAD = 6

    /**
     * Every `@Composable` function DECLARATION in [lines] with a body: the annotation (on its own line or before the
     * modifiers) opens one, the `fun` line starts its range, and the range ends on the line holding the body's opening
     * `{` or `=`, found by balancing parentheses after the parameter list opens — so a function-type parameter's `->`
     * and a default value's `=` or `{` inside the list end nothing. A declaration with no body (abstract) is skipped:
     * it compiles to no code. String and character literals and `//` comments are not read as brackets.
     */
    fun declarations(lines: List<String>): List<Declaration> {
        val out = mutableListOf<Declaration>()
        var i = 0
        while (i < lines.size) {
            if (!ANNOTATION.containsMatchIn(lines[i])) { i++; continue }
            val funLine = (i until minOf(lines.size, i + LOOKAHEAD)).firstOrNull { FUN.containsMatchIn(lines[it]) }
            if (funLine == null) { i++; continue }
            val name = FUN.find(lines[funLine])!!.groupValues[1]
            val end = bodyStart(lines, funLine)
            if (end != null) out += Declaration(name, (funLine + 1)..(end + 1))
            i = (end ?: funLine) + 1
        }
        return out
    }

    /** The 0-based line holding the body's `{` or `=`, or null when the declaration has none. */
    private fun bodyStart(lines: List<String>, from: Int): Int? {
        var depth = 0
        var opened = false
        for (n in from until lines.size) {
            val code = codeOf(lines[n])
            if (opened && depth == 0 && n > from && startsNewDeclaration(code)) return null
            for (ch in code) {
                when {
                    ch == '(' -> { depth++; opened = true }
                    ch == ')' -> depth--
                    opened && depth == 0 && (ch == '{' || ch == '=') -> return n
                }
            }
        }
        return null
    }

    private val DECLARATION_START = Regex("""^\s*(@|fun\b|val\b|var\b|class\b|interface\b|object\b|override\b|private\b|internal\b|public\b|protected\b|abstract\b|\})""")

    private fun startsNewDeclaration(code: String) = code.isNotBlank() && DECLARATION_START.containsMatchIn(code)

    /** [line] with string/char literal contents and a trailing `//` comment blanked. */
    internal fun codeOf(line: String): String {
        val sb = StringBuilder()
        var quote: Char? = null
        var k = 0
        while (k < line.length) {
            val ch = line[k]
            when {
                quote != null && ch == '\\' -> { sb.append(if (k + 1 < line.length) "  " else " "); k++ }
                quote != null && ch == quote -> { quote = null; sb.append(ch) }
                quote != null -> sb.append(' ')
                ch == '"' || ch == '\'' -> { quote = ch; sb.append(ch) }
                ch == '/' && line.getOrNull(k + 1) == '/' -> return sb.toString()
                else -> sb.append(ch)
            }
            k++
        }
        return sb.toString()
    }

    /** The `<package path>/<file name>` → contents index [judge] reads, over a module's main Kotlin [files]. */
    fun index(files: Iterable<File>): Map<String, List<List<String>>> =
        files.filter { it.extension == "kt" }
            .map { file ->
                val lines = file.readLines()
                val pkg = lines.firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) }.orEmpty()
                "${pkg.replace('.', '/')}/${file.name}".removePrefix("/") to lines
            }
            .groupBy({ it.first }, { it.second })

    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""")

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun Element.int(attr: String) = getAttribute(attr).ifEmpty { "0" }.toInt()
}

package app.snapsync.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The ZERO coverage gate's judgement (`docs/architecture.md`, "Coverage"), pure so it is tested without Gradle: given
 * a module's Kover `report.xml` and its main sources, every INSTRUCTION and BRANCH the report counts as missed fails
 * the module — except COMPILER-GENERATED GLUE, recognised by its shape, and the one thing a person writes that no input
 * may reach: a throw of `InvalidProgramState`. Each glue shape is code a compiler emits that no input can reach;
 * besides that throw, nothing a person wrote is ever excused. The shapes:
 *
 * - **Compose declaration glue** ([ComposableDeclarations]): the declaration lines of a `@Composable` function, from
 *   `fun` through the body's opening `{` (or an expression body's `=`). The Compose compiler attributes its glue
 *   there — restart groups, the `$changed`/`$default` bits, `skipToGroupEnd`, `updateScope` — and no test can reach
 *   all of it. Composable LAMBDAS' own restart glue never shows as a miss (measured 2026-10-07: every miss inside one
 *   of `:ui:*`'s 67 is a real branch).
 * - **Compose body-line glue** ([ComposeGlue]): a remembered lambda's validity arms, and the dead arm of an exhaustive
 *   `when` inside a composable — read from the bytecode, each as an allowance the line's misses must fit inside.
 * - **kotlinx-serialization's zero-mask missing-field check** ([SerializationGlue]): in a `@Serializable` class whose
 *   every field has a default, the plugin's deserialization constructor still opens with `if ((0 & seen) != 0)
 *   throwMissingFieldException(…)`. The mask is the constant 0, so the throw cannot happen. Recognised in the BYTECODE,
 *   and only those instructions and that one branch are excused: the rest of the class's header line is judged as
 *   ever, and a class with a required field (a mask other than 0) is never excused — its check is real and a test
 *   reaches it by leaving the field out.
 * - **A throw of `InvalidProgramState`** ([InvalidProgramStateGlue]): a state guaranteed never to happen
 *   (`docs/patterns.md`, "Invalid program state"), so no test can reach it. Recognised in the BYTECODE as exactly
 *   `new InvalidProgramState; dup; ldc "<because>"; invokespecial <init>(String); athrow` — an elvis's `pop` before it
 *   included — and the ONE conditional arm that alone leads into it. A block that computes anything else, a block two
 *   arms lead into, and an exception of any other type are not this shape: the line is judged as ever.
 *
 * The declaration ranges come from a source scan, and the scan is CHECKED against the bytecode: every composable
 * method the report lists (a `Composer` parameter in its descriptor, not a lambda) must match a scanned declaration of
 * the same name in the same file, and the reverse. A scanner that misreads a signature therefore fails the build
 * instead of excusing — or refusing — the wrong lines.
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
        /** The body lines excused by [ComposeGlue]'s two shapes, each printed so the glue's growth stays visible. */
        val composeGlue: List<GlueLine> = emptyList(),
    ) {
        val passes: Boolean get() = misses.isEmpty() && mismatches.isEmpty()
    }

    data class Miss(val file: String, val line: Int, val instructions: Int, val branches: Int, val text: String)

    data class Tally(val lines: Int, val instructions: Int, val branches: Int)

    /**
     * A glue site the BYTECODE shows: [instructions] and [branches] on [line] of [file] (`<package path>/<file name>`,
     * as the report names it) that no input reaches, in [site] (`<class>.<method>`).
     */
    data class Glue(
        val file: String,
        val line: Int,
        val instructions: Int,
        val branches: Int,
        val site: String,
        val shape: String = "zero-mask check",
    )

    /** A body line [ComposeGlue] excused: which shape(s), and the misses it held. */
    data class GlueLine(val file: String, val line: Int, val shapes: String, val instructions: Int, val branches: Int)

    private const val COMPOSER = "Landroidx/compose/runtime/Composer;"

    /**
     * Judge [report] (Kover's XML, JaCoCo-shaped) against [sources]: the module's main Kotlin files, keyed by
     * `<package path>/<file name>` exactly as the report names a source file. A key with several files is ambiguous
     * and excuses nothing.
     */
    fun judge(
        report: String,
        sources: Map<String, List<List<String>>>,
        glue: List<Glue> = emptyList(),
        composeGlue: Map<String, Map<Int, ComposeGlue.LineGlue>> = emptyMap(),
    ): Verdict {
        val root = parse(report)
        val judgement = Judgement(sources, sitesByLine(glue), composeGlue, fullyRun(root))
        root.children("package").forEach(judgement::judgePackage)
        return judgement.verdict(shapeProblems(root))
    }

    /** Two sites on one line (a throw's block on it, and the arm into another throw's) are excused together. */
    private fun sitesByLine(glue: List<Glue>): Map<Pair<String, Int>, Glue> =
        glue.groupBy { it.file to it.line }.mapValues { (_, sites) ->
            sites.reduce { a, b ->
                a.copy(
                    instructions = a.instructions + b.instructions,
                    branches = a.branches + b.branches,
                    site = "${a.site}, ${b.site}",
                )
            }
        }

    /** A line's missed instructions and branches: as the report counts them, less what has been excused so far. */
    private data class Missed(val instructions: Int, val branches: Int) {
        val none: Boolean get() = instructions == 0 && branches == 0
    }

    /** One report source file under judgement: its key, its lines (null when ambiguous), its declaration lines. */
    private class SourceFile(val key: String, val lines: List<String>?, val declarationLines: Set<Int>)

    /** One module's judgement, accumulated file by file and line by line into its [Verdict]. */
    private class Judgement(
        private val sources: Map<String, List<List<String>>>,
        private val glueAt: Map<Pair<String, Int>, Glue>,
        private val composeGlue: Map<String, Map<Int, ComposeGlue.LineGlue>>,
        private val fullyRun: Set<ComposeGlue.MethodRef>,
    ) {
        private val composeLines = mutableListOf<GlueLine>()
        private val misses = mutableListOf<Miss>()
        private val mismatches = mutableListOf<String>()
        private var excused = Tally(0, 0, 0)
        private val excusedGlue = mutableListOf<Glue>()

        fun judgePackage(pkg: Element) {
            val pkgPath = pkg.getAttribute("name")
            val composables = composableMethods(pkg)
            for (sf in pkg.children("sourcefile")) {
                val name = sf.getAttribute("name")
                judgeFile("$pkgPath/$name", sf, composables[name].orEmpty())
            }
        }

        fun verdict(shapeProblems: List<String>) =
            Verdict(misses, excused, mismatches + shapeProblems, excusedGlue, composeLines)

        /** Judges [sf]'s lines, after checking its scanned declarations against the bytecode's [expected] ones. */
        private fun judgeFile(key: String, sf: Element, expected: Set<String>) {
            val candidates = sources[key].orEmpty()
            val lines = candidates.singleOrNull()
            val declarations = lines?.let(ComposableDeclarations::scan).orEmpty()
            val scanned = declarations.map { it.name }.toSet()
            if (lines == null && expected.isNotEmpty()) {
                mismatches += "$key: ${candidates.size} source files match, so its composables cannot be located"
            } else if (expected != scanned) {
                mismatches += "$key: the bytecode's composables ${expected.sorted()} != the scanned ${scanned.sorted()}"
            }
            val file = SourceFile(key, lines, declarations.flatMap { it.lines }.toSet())
            for (line in sf.children("line")) {
                judgeLine(file, line.int("nr"), Missed(line.int("mi"), line.int("mb")))
            }
        }

        private fun judgeLine(file: SourceFile, nr: Int, reported: Missed) {
            val missed = if (reported.none) reported else withoutBytecodeGlue(file.key, nr, reported)
            if (missed.none) return
            val body = composeGlue[file.key]?.get(nr)
            val shapes = body?.let { bodyGlue(it, missed.instructions, missed.branches, fullyRun) }
            when {
                nr in file.declarationLines -> excused = Tally(
                    excused.lines + 1,
                    excused.instructions + missed.instructions,
                    excused.branches + missed.branches,
                )
                shapes != null -> composeLines += GlueLine(file.key, nr, shapes, missed.instructions, missed.branches)
                else -> {
                    val text = file.lines?.getOrNull(nr - 1)?.trim().orEmpty()
                    val hint = body?.let { sameLineHint(it, fullyRun) }.orEmpty()
                    misses += Miss(file.key, nr, missed.instructions, missed.branches, text + hint)
                }
            }
        }

        /** [missed] less the bytecode glue site on line [nr] of [key], when the line's misses hold all of it. */
        private fun withoutBytecodeGlue(key: String, nr: Int, missed: Missed): Missed {
            val site = glueAt[key to nr]
            return when {
                site == null -> missed
                missed.instructions < site.instructions || missed.branches < site.branches -> {
                    // The report counts less than the bytecode's unreachable block: it is not measuring what
                    // this reader recognised, so the glue cannot be told apart from the line's own code.
                    mismatches += "$key:$nr: ${site.site} holds ${site.instructions} instr / ${site.branches} " +
                        "branch of unreachable glue, but the line misses only ${missed.instructions} / " +
                        "${missed.branches}"
                    missed
                }
                else -> {
                    excusedGlue += site
                    Missed(missed.instructions - site.instructions, missed.branches - site.branches)
                }
            }
        }
    }

    /**
     * Whether [ComposeGlue]'s allowance on a body line holds its [mi]/[mb] misses, and under which shapes. The
     * remembered-lambda allowance counts only where no user branch shares the line in a composable method and every
     * other method with code there is one the report lists as run in full ([fullyRun]). A method the report does NOT
     * list still has its lines counted — Kover leaves out a lambda whose body sits on its caller's line, yet counts
     * that body in the line's totals (measured 2026-10-08, `:ui:screens`) — so such a line cannot tell an unrun body
     * from the cache's arms, and is never excused: the lambda's body goes on its own line, where an unrun one is a
     * miss.
     */
    private fun bodyGlue(g: ComposeGlue.LineGlue, mi: Int, mb: Int, fullyRun: Set<ComposeGlue.MethodRef>): String? {
        val remembered = g.remembered.takeIf { !g.userBranch && g.otherMethods.all { it in fullyRun } }
            ?: ComposeGlue.Allowance.NONE
        val allowed = remembered + g.deadArm
        if (mi > allowed.instructions || mb > allowed.branches) return null
        return listOfNotNull(
            "remembered lambda".takeIf { remembered != ComposeGlue.Allowance.NONE },
            "dead when arm".takeIf { g.deadArm != ComposeGlue.Allowance.NONE },
        ).joinToString(" + ").ifEmpty { null }
    }

    /** Why a remembered lambda's line was not excused, where the reason is a body the report cannot vouch for. */
    private fun sameLineHint(g: ComposeGlue.LineGlue, fullyRun: Set<ComposeGlue.MethodRef>): String? =
        "   [a remembered lambda's body on its line, which Kover does not list as run: move the body to its own line]"
            .takeIf {
                g.remembered != ComposeGlue.Allowance.NONE && !g.userBranch && g.otherMethods.any { it !in fullyRun }
            }

    /** The methods the report lists with no instruction or branch missed. */
    private fun fullyRun(root: Element): Set<ComposeGlue.MethodRef> =
        root.children("package").flatMap { it.children("class") }.flatMap { cls ->
            cls.children("method").filter { m ->
                m.children("counter")
                    .none { it.getAttribute("type") in setOf("INSTRUCTION", "BRANCH") && it.int("missed") > 0 }
            }.map { ComposeGlue.MethodRef(cls.getAttribute("name"), it.getAttribute("name"), it.getAttribute("desc")) }
        }.toSet()

    /**
     * The gate reads per-LINE counts, so it guards the report shape it relies on: a report with no package, or whose
     * line counts do not add up to its own totals (measured equal on every module, 2026-10-07), would otherwise pass
     * vacuously after a Kover upgrade moved the counts somewhere this reader does not look.
     */
    private fun shapeProblems(root: Element): List<String> {
        val lines = root.children("package").flatMap { it.children("sourcefile") }.flatMap { it.children("line") }
        if (lines.isEmpty()) return listOf("the report holds no package line counts — has Kover's XML shape changed?")
        val totals = root.children("counter")
            .associate { it.getAttribute("type") to it.int("missed") + it.int("covered") }
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
                    .map {
                        cls.getAttribute("sourcefilename") to
                            it.getAttribute("name").substringBefore('$').substringBefore('-')
                    }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

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
}

private fun Element.children(tag: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
}

private fun Element.int(attr: String) = getAttribute(attr).ifEmpty { "0" }.toInt()

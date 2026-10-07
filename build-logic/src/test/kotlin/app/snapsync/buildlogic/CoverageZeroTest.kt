package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageZeroTest {

    private val screen = """
        package app.ui

        @Composable
        fun Screen(
            title: String,
            onPick: (Int) -> Unit,
            hint: String = "a (paren",
            footer: @Composable () -> Unit = {},
        ) {
            if (title.isEmpty()) Text("none")
        }

        @Composable
        private fun Label(text: String) = Text(text)

        fun plain(x: Int) {
            if (x > 0) println(x)
        }
    """.trimIndent().lines()

    @Test
    fun `a declaration runs from fun through the body's brace, across function types, defaults and literals`() {
        assertEquals(
            listOf(CoverageZero.Declaration("Screen", 4..9), CoverageZero.Declaration("Label", 14..14)),
            CoverageZero.declarations(screen),
        )
    }

    @Test
    fun `an abstract composable has no body and no range`() {
        val src = """
            interface Slot {
                @Composable
                fun Render(x: Int)

                @Composable
                fun Other(): Unit
            }
        """.trimIndent().lines()
        assertEquals(emptyList(), CoverageZero.declarations(src))
    }

    @Test
    fun `a composable function type is not a declaration`() {
        val src = listOf("class A(", "    val content: @Composable () -> Unit,", ")")
        assertEquals(emptyList(), CoverageZero.declarations(src))
    }

    @Test
    fun `misses on declaration lines are excused and counted, a body miss fails`() {
        val v = CoverageZero.judge(
            report(
                methods = listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE, "Screen\$lambda\$0" to COMPOSABLE),
                lines = listOf(Line(4, mi = 3, mb = 2), Line(9, mi = 1), Line(10, mi = 2, mb = 1), Line(14, mi = 1)),
            ),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
        )
        assertEquals(CoverageZero.Tally(lines = 3, instructions = 5, branches = 2), v.excused)
        assertEquals(listOf(10), v.misses.map { it.line })
        assertEquals("if (title.isEmpty()) Text(\"none\")", v.misses.single().text)
        assertEquals(emptyList(), v.mismatches)
    }

    @Test
    fun `a fully covered module passes`() {
        val v = CoverageZero.judge(
            report(listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE), listOf(Line(10))),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
        )
        assertTrue(v.passes)
    }

    @Test
    fun `a composable the scan did not find fails rather than excusing nothing silently`() {
        val v = CoverageZero.judge(
            report(listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE, "Hidden" to COMPOSABLE), listOf(Line(10))),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
        )
        assertTrue(v.mismatches.single().contains("Hidden"), v.mismatches.toString())
    }

    @Test
    fun `a declaration the bytecode does not hold fails too`() {
        val v = CoverageZero.judge(
            report(listOf("Screen" to COMPOSABLE), listOf(Line(10))),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
        )
        assertTrue(v.mismatches.single().contains("Label"), v.mismatches.toString())
    }

    @Test
    fun `mangled names match their declarations - an internal member's, a value-class taker's`() {
        val v = CoverageZero.judge(
            report(listOf("Screen\$ui" to COMPOSABLE, "Label-RPmYEkk" to COMPOSABLE, "plain" to "(I)V"), listOf(Line(10))),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
        )
        assertEquals(emptyList(), v.mismatches)
    }

    @Test
    fun `a report whose line counts do not add up to its totals is refused`() {
        val xml = report(listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE), listOf(Line(10)))
            .replace("""<counter type="INSTRUCTION" missed="0" covered="1"/></report>""",
                """<counter type="INSTRUCTION" missed="7" covered="1"/></report>""")
        val v = CoverageZero.judge(xml, mapOf("app/ui/Screen.kt" to listOf(screen)))
        assertTrue(v.mismatches.single().contains("INSTRUCTION"), v.mismatches.toString())
    }

    @Test
    fun `a report with no line counts is refused, never passed vacuously`() {
        val v = CoverageZero.judge("""<report name="x"></report>""", emptyMap())
        assertTrue(!v.passes && v.mismatches.single().contains("no package"), v.mismatches.toString())
    }

    @Test
    fun `a miss in a file with no located source still fails`() {
        val v = CoverageZero.judge(report(emptyList(), listOf(Line(3, mi = 1))), emptyMap())
        assertEquals(listOf(3), v.misses.map { it.line })
    }

    @Test
    fun `literals and comments are not code`() {
        assertEquals("""val s = "   " """, CoverageZero.codeOf("""val s = "({=" // {"""))
        assertEquals("""'\'' + x""".length, CoverageZero.codeOf("""'\'' + x""").length)
    }

    @Test
    fun `the index keys a file by its package path`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = dir.resolve("Screen.kt").apply { writeText("package app.ui\n\nfun x() {}\n") }
        val root = dir.resolve("Root.kt").apply { writeText("fun y() {}\n") }
        assertEquals(setOf("app/ui/Screen.kt", "Root.kt"), CoverageZero.index(listOf(f, root, dir.resolve("a.txt"))).keys)
        dir.deleteRecursively()
    }

    private data class Line(val nr: Int, val mi: Int = 0, val mb: Int = 0)

    private fun report(methods: List<Pair<String, String>>, lines: List<Line>): String {
        val ms = methods.joinToString("") { (n, d) -> """<method name="$n" desc="$d"/>""" }
        val ls = lines.joinToString("") { """<line nr="${it.nr}" mi="${it.mi}" ci="1" mb="${it.mb}" cb="0"/>""" }
        val mi = lines.sumOf { it.mi }
        val mb = lines.sumOf { it.mb }
        return """<report name="x"><package name="app/ui"><class name="app/ui/ScreenKt" sourcefilename="Screen.kt">$ms</class>""" +
            """<sourcefile name="Screen.kt">$ls</sourcefile></package>""" +
            """<counter type="BRANCH" missed="$mb" covered="0"/>""" +
            """<counter type="INSTRUCTION" missed="$mi" covered="${lines.size}"/></report>"""
    }

    private companion object {
        const val COMPOSABLE = "(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V"
    }
}

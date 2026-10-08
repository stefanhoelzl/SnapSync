package app.snapsync.buildlogic

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
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

    // ---- kotlinx-serialization's zero-mask missing-field check ---------------------------------------------------

    @Test
    fun `a zero-mask missing-field check is recognised with its block's instructions and its one branch`() {
        assertEquals(
            listOf(CoverageZero.Glue("app/ui/Screen.kt", 17, instructions = 5, branches = 1, site = "app.ui.Settings.<init>")),
            CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0))),
        )
    }

    @Test
    fun `a class with a required field is never excused - its check is real and a test reaches it`() {
        assertEquals(emptyList(), CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 1))))
        assertEquals(emptyList(), CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0x40))))
    }

    @Test
    fun `nothing but the exact shape is recognised`() {
        assertEquals(emptyList(), CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0, slot = 2))), "another slot")
        assertEquals(emptyList(), CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0, throws = false))), "no throw")
        assertEquals(emptyList(), CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0, marker = false))), "no marker")
    }

    @Test
    fun `only the glue's own counts are excused - the rest of its line is judged as ever`() {
        val glue = CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0)))
        val composables = listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE)

        val alone = CoverageZero.judge(report(composables, listOf(Line(17, mi = 5, mb = 1))), mapOf("app/ui/Screen.kt" to listOf(screen)), glue)
        assertTrue(alone.passes)
        assertEquals(glue, alone.excusedGlue)

        val more = CoverageZero.judge(report(composables, listOf(Line(17, mi = 7, mb = 2))), mapOf("app/ui/Screen.kt" to listOf(screen)), glue)
        assertEquals(listOf(CoverageZero.Miss("app/ui/Screen.kt", 17, 2, 1, "if (x > 0) println(x)")), more.misses)
    }

    @Test
    fun `a line missing less than the recognised glue is a mismatch, not an excuse`() {
        val glue = CoverageZero.serializationGlue(listOf(deserializingConstructor(mask = 0)))
        val v = CoverageZero.judge(
            report(listOf("Screen" to COMPOSABLE, "Label" to COMPOSABLE), listOf(Line(17, mi = 3))),
            mapOf("app/ui/Screen.kt" to listOf(screen)),
            glue,
        )
        assertEquals(emptyList(), v.excusedGlue)
        assertEquals(1, v.mismatches.size, v.mismatches.toString())
        assertTrue(!v.passes)
    }

    /**
     * A class shaped like the plugin's output for `app.ui.Settings` in `Screen.kt`: a deserialization constructor (with
     * the `SerializationConstructorMarker` unless [marker] is false) opening with `[mask] & seen` on [slot], whose
     * skipped block ends in `throwMissingFieldException` unless [throws] is false — all attributed to line 17.
     */
    private fun deserializingConstructor(mask: Int, slot: Int = 1, throws: Boolean = true, marker: Boolean = true): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "app/ui/Settings", null, "java/lang/Object", null)
        cw.visitSource("Screen.kt", null)
        val desc = if (marker) "(IZLkotlinx/serialization/internal/SerializationConstructorMarker;)V" else "(IZ)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, "<init>", desc, null, null)
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(17, start)
        if (mask in 0..5) mv.visitInsn(Opcodes.ICONST_0 + mask) else mv.visitIntInsn(Opcodes.BIPUSH, mask)
        mv.visitVarInsn(Opcodes.ILOAD, slot)
        mv.visitInsn(Opcodes.IAND)
        val skip = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, skip)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitFieldInsn(Opcodes.GETSTATIC, "app/ui/Settings\$\$serializer", "INSTANCE", "Lapp/ui/Settings\$\$serializer;")
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "app/ui/Settings\$\$serializer", "getDescriptor", "()Ljava/lang/Object;", false)
        val owner = if (throws) "kotlinx/serialization/internal/PluginExceptionsKt" else "app/ui/Elsewhere"
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "throwMissingFieldException", "(IILjava/lang/Object;)V", false)
        mv.visitLabel(skip)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
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

package app.snapsync.buildlogic

import app.snapsync.buildlogic.ComposeGlue.Allowance
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.ATHROW
import org.objectweb.asm.Opcodes.BIPUSH
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.GOTO
import org.objectweb.asm.Opcodes.IAND
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.ICONST_1
import org.objectweb.asm.Opcodes.ICONST_4
import org.objectweb.asm.Opcodes.ICONST_M1
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.IFNE
import org.objectweb.asm.Opcodes.IFNULL
import org.objectweb.asm.Opcodes.IF_ICMPEQ
import org.objectweb.asm.Opcodes.IF_ICMPLE
import org.objectweb.asm.Opcodes.IF_ICMPNE
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.Opcodes.INSTANCEOF
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.IOR
import org.objectweb.asm.Opcodes.ISTORE
import org.objectweb.asm.Opcodes.IXOR
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.SIPUSH
import org.objectweb.asm.Opcodes.V17
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ComposeGlue]'s two body-line shapes, over bytecode written the way the Compose compiler writes it (the instruction
 * sequences were read off `:ui:screens`' classes with `javap`), and [CoverageZero.judge]'s use of their allowances.
 */
class ComposeGlueTest {

    @Test
    fun `a remembered lambda's validity test is an allowance of its instructions and two arms per jump`() {
        val glue = scan { line(20); validityTest() }.getValue(20)
        assertEquals(Allowance(instructions = 17, branches = 6), glue.remembered)
        assertFalse(glue.userBranch)
    }

    @Test
    fun `the mask checks are read through the compiler's copy of the changed bits`() {
        // The compiler reads `$changed` through a `$dirty` local it assigns from it: `$dirty = $changed`.
        val glue = scan {
            line(19)
            visitVarInsn(ILOAD, CHANGED)
            visitVarInsn(ISTORE, DIRTY)
            line(20)
            validityTest(bits = DIRTY)
        }.getValue(20)
        assertEquals(Allowance(instructions = 17, branches = 6), glue.remembered)
        assertFalse(glue.userBranch)
    }

    @Test
    fun `its misses are excused when they fit, and the line is printed`() {
        val v = judge(scan { line(20); validityTest() }, Line(20, mi = 8, mb = 3))
        assertEquals(emptyList(), v.misses)
        assertEquals(listOf(CoverageZero.GlueLine(KEY, 20, "remembered lambda", 8, 3)), v.composeGlue)
    }

    @Test
    fun `a user if on the same line is NOT excused, even beside the shape`() {
        val glue = scan {
            line(20)
            validityTest()
            visitVarInsn(ALOAD, 0)
            val skip = Label()
            visitJumpInsn(IFNULL, skip)
            visitLabel(skip)
        }
        assertTrue(glue.getValue(20).userBranch)
        assertEquals(listOf(20), judge(glue, Line(20, mi = 8, mb = 3)).misses.map { it.line })
    }

    @Test
    fun `the trace epilogue on a remember's return line is not a user branch`() {
        // `return remember(language, formats) { formats(language) }`: the compiler's trace epilogue shares its line.
        val glue = scan {
            line(27)
            rememberKeyedOnParameters()
            visitVarInsn(ALOAD, 5)
            visitVarInsn(ASTORE, 4)
            traceGuard("traceEventEnd", "()V")
        }
        assertFalse(glue.getValue(27).userBranch)
        assertEquals(emptyList(), judge(glue, Line(27, mi = 5, mb = 1)).misses)
    }

    @Test
    fun `the trace prologue is not a user branch either`() {
        val glue = scan {
            line(25)
            traceGuard("traceEventStart", "(IIILjava/lang/String;)V") {
                visitLdcInsn(1893993446)
                visitVarInsn(ILOAD, CHANGED)
                visitInsn(ICONST_M1)
                visitLdcInsn("Screen (Screen.kt:24)")
            }
        }
        assertFalse(glue[25]?.userBranch ?: false)
    }

    @Test
    fun `a user branch on isTraceInProgress is the user's, and a user if beside the epilogue still withholds`() {
        val userGuard = scan {
            line(27)
            visitMethodInsn(INVOKESTATIC, COMPOSER_KT, "isTraceInProgress", "()Z", false)
            val skip = Label()
            visitJumpInsn(IFEQ, skip)
            visitMethodInsn(INVOKESTATIC, CLASS, "log", "()V", false)
            visitLabel(skip)
        }
        assertTrue(userGuard.getValue(27).userBranch)
        val beside = scan {
            line(27)
            rememberKeyedOnParameters()
            visitVarInsn(ALOAD, 0)
            val skip = Label()
            visitJumpInsn(IFNULL, skip)
            visitLabel(skip)
            traceGuard("traceEventEnd", "()V")
        }
        assertTrue(beside.getValue(27).userBranch)
        assertEquals(listOf(27), judge(beside, Line(27, mi = 5, mb = 1)).misses.map { it.line })
    }

    @Test
    fun `a lambda body on the line withholds the allowance unless the report lists it run in full`() {
        val glue = scan(lambdaOnLine = 20) { line(20); validityTest() }
        assertEquals(listOf(20), judge(glue, Line(20, mi = 8, mb = 3), lambdaMissed = 4).misses.map { it.line })
        assertEquals(emptyList(), judge(glue, Line(20, mi = 8, mb = 3), lambdaMissed = 0).misses)
        // Kover leaves a body on its caller's line out of its method list while counting it in the line: unlisted, the
        // line cannot tell an unrun body from the cache's arms, so it is not excused.
        val unlisted = judge(glue, Line(20, mi = 8, mb = 3), lambdaMissed = null).misses.single()
        assertTrue("move the body to its own line" in unlisted.text, unlisted.text)
    }

    @Test
    fun `more misses than the shape accounts for fail — Kover counting it differently fails loudly`() {
        val glue = scan { line(20); validityTest() }
        assertEquals(listOf(20), judge(glue, Line(20, mi = 18, mb = 3)).misses.map { it.line })
        assertEquals(listOf(20), judge(glue, Line(20, mi = 8, mb = 7)).misses.map { it.line })
    }

    @Test
    fun `a mask check that stores no boolean is not the shape`() {
        val glue = scan {
            line(20)
            visitVarInsn(ILOAD, CHANGED)
            visitIntInsn(BIPUSH, 3)
            visitInsn(IAND)
            val skip = Label()
            visitJumpInsn(IFEQ, skip)
            visitLabel(skip)
        }.getValue(20)
        assertEquals(Allowance.NONE, glue.remembered)
        assertTrue(glue.userBranch)
    }

    @Test
    fun `the dead arm of a when is its group calls and the jump into it, the throw left to Kover`() {
        val glue = scan { deadArm() }
        assertEquals(Allowance(instructions = 3, branches = 1), glue.getValue(30).deadArm)
        assertEquals(Allowance(instructions = 2, branches = 0), glue.getValue(32).deadArm)
        val v = judge(glue, Line(30, mi = 3, mb = 1), Line(32, mi = 2))
        assertEquals(emptyList(), v.misses)
        assertEquals(listOf("dead when arm", "dead when arm"), v.composeGlue.map { it.shapes })
    }

    @Test
    fun `the live arm of the same jump is the user's - its miss fails`() {
        assertEquals(listOf(30), judge(scan { deadArm() }, Line(30, mi = 3, mb = 2)).misses.map { it.line })
    }

    @Test
    fun `a when's throw with no Compose group around it is no glue`() {
        val glue = scan {
            line(40)
            throwNoWhen()
        }
        assertNull(glue[40]?.deadArm?.takeIf { it != Allowance.NONE })
    }

    @Test
    fun `a method without a Composer is never read for the shapes`() {
        val bytes = classOf("plain", "(Ljava/lang/Object;I)V") { line(20); validityTest() }
        val glue = ComposeGlue.scan(listOf(bytes)).getValue(KEY).getValue(20)
        assertEquals(Allowance.NONE, glue.remembered)
        assertEquals(1, glue.otherMethods.size)
    }

    // ---- bytecode as the compiler writes it -------------------------------------------------------------------------

    private fun MethodVisitor.line(nr: Int) {
        val l = Label()
        visitLabel(l)
        visitLineNumber(nr, l)
    }

    /** `onClick = { … }`'s cache test: two `$changed` mask checks and a `changedInstance`, feeding one boolean. */
    private fun MethodVisitor.validityTest(bits: Int = CHANGED) {
        val yes = Label()
        val no = Label()
        val stored = Label()
        visitVarInsn(ILOAD, bits)
        visitIntInsn(SIPUSH, 7168)
        visitInsn(IAND)
        visitIntInsn(SIPUSH, 2048)
        visitJumpInsn(IF_ICMPEQ, yes)
        visitVarInsn(ILOAD, bits)
        visitIntInsn(SIPUSH, 4096)
        visitInsn(IAND)
        visitJumpInsn(IFEQ, no)
        visitVarInsn(ALOAD, COMPOSER_SLOT)
        visitVarInsn(ALOAD, 0)
        visitMethodInsn(INVOKEINTERFACE, COMPOSER, "changedInstance", "(Ljava/lang/Object;)Z", true)
        visitJumpInsn(IFEQ, no)
        visitLabel(yes)
        visitInsn(ICONST_1)
        visitJumpInsn(GOTO, stored)
        visitLabel(no)
        visitInsn(ICONST_0)
        visitLabel(stored)
        visitVarInsn(ISTORE, 3)
    }

    /**
     * `remember(language, formats)`'s cache test, read off `rememberDateFormats` (`formats` in slot 0, `language` in 3):
     * `changed(language) | ((dirty & 14 ^ 6) > 4 && changed(formats) || (dirty & 6) == 4)`.
     */
    private fun MethodVisitor.rememberKeyedOnParameters() {
        val unchanged = Label()
        val yes = Label()
        val no = Label()
        val or = Label()
        visitVarInsn(ALOAD, COMPOSER_SLOT)
        visitVarInsn(ALOAD, 3)
        visitMethodInsn(INVOKEINTERFACE, COMPOSER, "changed", "(Ljava/lang/Object;)Z", true)
        visitVarInsn(ILOAD, CHANGED)
        visitIntInsn(BIPUSH, 14)
        visitInsn(IAND)
        visitIntInsn(BIPUSH, 6)
        visitInsn(IXOR)
        visitInsn(ICONST_4)
        visitJumpInsn(IF_ICMPLE, unchanged)
        visitVarInsn(ALOAD, COMPOSER_SLOT)
        visitVarInsn(ALOAD, 0)
        visitMethodInsn(INVOKEINTERFACE, COMPOSER, "changed", "(Ljava/lang/Object;)Z", true)
        visitJumpInsn(IFNE, yes)
        visitLabel(unchanged)
        visitVarInsn(ILOAD, CHANGED)
        visitIntInsn(BIPUSH, 6)
        visitInsn(IAND)
        visitInsn(ICONST_4)
        visitJumpInsn(IF_ICMPNE, no)
        visitLabel(yes)
        visitInsn(ICONST_1)
        visitJumpInsn(GOTO, or)
        visitLabel(no)
        visitInsn(ICONST_0)
        visitLabel(or)
        visitInsn(IOR)
        visitVarInsn(ISTORE, 7)
    }

    /** `if (isTraceInProgress()) <event>(…)`, [args] pushing the event's arguments. */
    private fun MethodVisitor.traceGuard(event: String, desc: String, args: MethodVisitor.() -> Unit = {}) {
        val skip = Label()
        visitMethodInsn(INVOKESTATIC, COMPOSER_KT, "isTraceInProgress", "()Z", false)
        visitJumpInsn(IFEQ, skip)
        args()
        visitMethodInsn(INVOKESTATIC, COMPOSER_KT, event, desc, false)
        visitLabel(skip)
    }

    /** A sealed `when`'s last `is` arm (line 30) and its dead default: a replace group around the throw (30–32). */
    private fun MethodVisitor.deadArm() {
        val dead = Label()
        val after = Label()
        line(30)
        visitVarInsn(ALOAD, 0)
        visitTypeInsn(INSTANCEOF, "app/ui/Progress")
        visitJumpInsn(IFEQ, dead)
        line(31)
        visitJumpInsn(GOTO, after)
        visitLabel(dead)
        visitLineNumber(30, dead)
        visitVarInsn(ALOAD, COMPOSER_SLOT)
        visitLdcInsn(1254019890)
        visitMethodInsn(INVOKEINTERFACE, COMPOSER, "startReplaceGroup", "(I)V", true)
        line(32)
        visitVarInsn(ALOAD, COMPOSER_SLOT)
        visitMethodInsn(INVOKEINTERFACE, COMPOSER, "endReplaceGroup", "()V", true)
        throwNoWhen()
        visitLabel(after)
        line(33)
    }

    private fun MethodVisitor.throwNoWhen() {
        visitTypeInsn(NEW, NO_WHEN)
        visitInsn(DUP)
        visitMethodInsn(INVOKESPECIAL, NO_WHEN, "<init>", "()V", false)
        visitInsn(ATHROW)
    }

    private fun scan(lambdaOnLine: Int? = null, body: MethodVisitor.() -> Unit): Map<Int, ComposeGlue.LineGlue> =
        ComposeGlue.scan(listOf(classOf("Screen", SCREEN, lambdaOnLine, body))).getValue(KEY)

    private fun classOf(
        name: String,
        desc: String,
        lambdaOnLine: Int? = null,
        body: MethodVisitor.() -> Unit,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, ACC_PUBLIC, CLASS, null, "java/lang/Object", null)
        cw.visitSource("Screen.kt", null)
        cw.visitMethod(ACC_PUBLIC or ACC_STATIC, name, desc, null, null).apply {
            visitCode()
            body()
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (lambdaOnLine != null) {
            cw.visitMethod(ACC_STATIC, LAMBDA, "()V", null, null).apply {
                visitCode()
                line(lambdaOnLine)
                visitInsn(RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private data class Line(val nr: Int, val mi: Int = 0, val mb: Int = 0)

    /** A report holding [lines]; [lambdaMissed] lists the lambda method with that many missed instructions. */
    private fun judge(glue: Map<Int, ComposeGlue.LineGlue>, vararg lines: Line, lambdaMissed: Int? = null): CoverageZero.Verdict {
        val method = lambdaMissed?.let {
            """<method name="$LAMBDA" desc="()V"><counter type="INSTRUCTION" missed="$it" covered="1"/>""" +
                """<counter type="BRANCH" missed="0" covered="0"/></method>"""
        }.orEmpty()
        val ls = lines.joinToString("") { """<line nr="${it.nr}" mi="${it.mi}" ci="1" mb="${it.mb}" cb="0"/>""" }
        val xml = """<report name="x"><package name="app/ui"><class name="$CLASS" sourcefilename="Screen.kt">$method</class>""" +
            """<sourcefile name="Screen.kt">$ls</sourcefile></package>""" +
            """<counter type="BRANCH" missed="${lines.sumOf { it.mb }}" covered="0"/>""" +
            """<counter type="INSTRUCTION" missed="${lines.sumOf { it.mi }}" covered="${lines.size}"/></report>"""
        return CoverageZero.judge(xml, emptyMap(), composeGlue = mapOf(KEY to glue))
    }

    private companion object {
        const val KEY = "app/ui/Screen.kt"
        const val CLASS = "app/ui/ScreenKt"
        const val LAMBDA = "Screen\$lambda\$0"
        const val COMPOSER = "androidx/compose/runtime/Composer"
        const val COMPOSER_KT = "androidx/compose/runtime/ComposerKt"
        const val NO_WHEN = "kotlin/NoWhenBranchMatchedException"
        const val SCREEN = "(Ljava/lang/Object;Landroidx/compose/runtime/Composer;I)V"
        const val COMPOSER_SLOT = 1
        const val CHANGED = 2
        const val DIRTY = 4
    }
}

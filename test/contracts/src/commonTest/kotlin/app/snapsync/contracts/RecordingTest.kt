package app.snapsync.contracts

import app.snapsync.model.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingTest {

    private val text = """
        # contract: SecureStore
        # host: IOS_DEVICE_APP
        [B]
        remove(acct=b) -> -25300
        [A]
        add(acct=a pdmn=ck) -> 0
        find(acct=a) -> 0 pdmn=ck
    """.trimIndent() + "\n"

    @Test
    fun `render sorts blocks by clause id and round-trips`() {
        val parsed = Recording.parse(text)
        assertEquals("IOS_DEVICE_APP", parsed.host)
        val rendered = parsed.render()
        assertTrue(rendered.indexOf("[A]") < rendered.indexOf("[B]"), rendered)
        assertEquals(parsed.blocks, Recording.parse(rendered).blocks)
        assertEquals(rendered, Recording.parse(rendered).render(), "render is a fixed point")
    }

    @Test
    fun `malformed input names the line`() {
        val e = assertFailsWith<IllegalArgumentException> { Recording.parse("[A]\nno arrow here\n") }
        assertTrue("line 2" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalArgumentException> { Recording.parse("add() -> 0\n") }
        assertFailsWith<IllegalArgumentException> { Recording.parse("[A]\n[A]\n") }
    }

    @Test
    fun `the recorder files seeding and clause calls under the open clause`() {
        val r = Recorder()
        r.open("A")
        r.record("seed()", "0")
        r.record("read()", "1")
        r.open("B")
        r.record("read()", "2")
        val rec = r.recording(listOf("host" to "IOS_DEVICE_APP"))
        assertEquals(listOf(Exchange("seed()", "0"), Exchange("read()", "1")), rec.blocks["A"])
        assertEquals(listOf(Exchange("read()", "2")), rec.blocks["B"])
    }

    @Test
    fun `a call outside any clause is refused`() {
        assertFailsWith<IllegalStateException> { Recorder().record("x()", "0") }
    }

    @Test
    fun `the replayer answers exactly and in order`() {
        val p = Replayer("A", listOf(Exchange("a()", "1"), Exchange("b()", "2")))
        assertEquals("1", p.answer("a()"))
        assertEquals("2", p.answer("b()"))
    }

    @Test
    fun `a reordered or different or extra call diverges`() {
        assertFailsWith<Divergence> { Replayer("A", listOf(Exchange("a()", "1"))).answer("b()") }
        val p = Replayer("A", listOf(Exchange("a()", "1")))
        p.answer("a()")
        assertFailsWith<Divergence> { p.answer("a()") }
    }

    @Test
    fun `a recorded call never made diverges on exhaustion`() {
        val p = Replayer("A", listOf(Exchange("a()", "1"), Exchange("b()", "2")))
        p.answer("a()")
        assertFailsWith<Divergence> { p.assertExhausted() }
        p.answer("b()")
        p.assertExhausted()
    }

    @Test
    fun `volatile keys are masked and nothing else is`() {
        assertEquals(
            "0 cdat=<masked> pdmn=ck mdat=<masked> v_Data=abc",
            maskKeys("0 cdat=2026-09-22 pdmn=ck mdat=x v_Data=abc", setOf("cdat", "mdat")),
        )
    }

    @Test
    fun `a recording is named per grant only where the binding declares one`() {
        assertEquals("SecureStore@IOS_DEVICE_APP", recordingName("SecureStore", Host.IOS_DEVICE_APP, null))
        assertEquals(
            "UploadExtensionRegistry@IOS_DEVICE_APP.LIMITED",
            recordingName("UploadExtensionRegistry", Host.IOS_DEVICE_APP, PermissionStatus.LIMITED),
        )
    }

    @Test
    fun `the header carries the grant where one was declared`() {
        assertNull(Recording.parse(text).grant)
        val granted = Recording.parse("# contract: X\n# host: IOS_DEVICE_APP\n# grant: GRANTED\n[A]\nc -> a\n")
        assertEquals("GRANTED", granted.grant)
    }
}

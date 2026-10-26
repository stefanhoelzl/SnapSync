package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals

/** A file operation's answers: what came back, or one of the four ways nothing did — each a failure. */
class FileResultsTest {

    private fun told(result: FileResult<Int>): String = when (result) {
        is FileResult.Ok -> "ok ${result.value}"
        is FileFailure -> "failed"
    }

    @Test
    fun `every answer but an ok is a failure`() {
        assertEquals("ok 1", told(FileResult.Ok(1)))
        val failures = listOf(
            FileResult.NotFound,
            FileResult.AreaUnavailable,
            FileResult.Denied("locked", 13),
            FileResult.Failed("io"),
        )
        assertEquals(List(4) { "failed" }, failures.map(::told))
        assertEquals(FileResult.Denied("locked", 13), FileResult.Denied("locked", 13))
        assertEquals(null, FileResult.Failed("io").code)
        assertEquals(true, FileTail(ByteArray(2), cut = true).cut)
    }
}

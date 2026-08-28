package app.snapsync.ports

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The enter/exit wrapper every platform invocation, app entry point and background trigger goes
 * through (capability `diagnostic-logging`, D3). Two of its properties are load-bearing and neither is
 * visible from a call site.
 *
 * **A throw is logged at Warn whatever [Severity] the call site chose, and re-thrown unchanged.** The
 * per-item entry points pass `Debug` deliberately — at `Info` a single large import flushes the crash
 * reporter's bounded breadcrumb window and rolls the size-capped device log before anyone reads it. If
 * that `Debug` also swallowed the *failure* line's severity, the one event worth keeping would be the
 * one discarded. And the throwable must reach the caller untouched: this wrapper is diagnostics, never
 * a `catch`.
 *
 * **The ambient scope is exited even when the block throws.** `enter`/`exit` set the prefix downstream
 * lines trace back to; an exit skipped on the failure path would leave every later line in the process
 * labelled with the entry point that died, which is worse than no prefix at all — it attributes
 * unrelated work to a failure.
 */
class InvocationTest {

    private class Capturing : LogWriter() {
        val lines: MutableList<Pair<Severity, String>> = mutableListOf()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
    }

    /** Records the ambient-scope handshake; `enter` answers whether THIS call owns the scope. */
    private class RecordingScope(private val owns: Boolean = true) : LogScope {
        val entered = mutableListOf<String>()
        val exited = mutableListOf<Boolean>()
        override fun enter(name: String): Boolean {
            entered += name
            return owns
        }
        override fun exit(owned: Boolean) {
            exited += owned
        }
    }

    private fun logger(writer: Capturing) =
        Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(writer)), "test")

    @Test
    fun `a successful call logs enter and exit at the chosen severity and returns the value`() {
        val captured = Capturing()
        val scope = RecordingScope()

        val value = logger(captured).invocation(
            scope,
            "onForeground",
            params = "event=E",
            result = { value: String -> "ok=$value" },
        ) { "done" }

        assertEquals("done", value)
        assertEquals(Severity.Info, captured.lines[0].first)
        assertEquals("→ onForeground(event=E)", captured.lines[0].second)
        assertTrue(
            captured.lines[1].second.startsWith("← onForeground = ok=done ("),
            "unexpected exit line: ${captured.lines[1].second}",
        )
        assertEquals(listOf("onForeground"), scope.entered)
        assertEquals(listOf(true), scope.exited)
    }

    @Test
    fun `an empty params and an empty result render no parentheses and no equals`() {
        // The call site controls verbosity: a blanket `toString()` of a large or expensive object is
        // exactly what these defaults exist to avoid, so the bare shapes have to stay bare.
        val captured = Capturing()

        logger(captured).invocation(RecordingScope(), "process") { }

        assertEquals("→ process", captured.lines[0].second)
        assertTrue(captured.lines[1].second.startsWith("← process ("), captured.lines[1].second)
    }

    @Test
    fun `a per-item entry point's Debug severity carries through to both routine lines`() {
        // `Debug` is what keeps a large import from flushing the breadcrumb window and rolling the log.
        val captured = Capturing()

        logger(captured).invocation(RecordingScope(), "onAssetChanged", severity = Severity.Debug) { }

        assertEquals(listOf(Severity.Debug, Severity.Debug), captured.lines.map { it.first })
    }

    @Test
    fun `a throw is reported at Warn whatever severity the call site chose and is re-thrown unchanged`() {
        val captured = Capturing()
        val scope = RecordingScope()
        val boom = IllegalStateException("boom")

        val thrown = assertFailsWith<IllegalStateException> {
            logger(captured).invocation(scope, "process", severity = Severity.Debug) { throw boom }
        }

        assertEquals(boom, thrown, "the wrapper is diagnostics, never a catch")
        // Entry at the chosen severity; the failure at Warn regardless — it is never the routine case.
        assertEquals(Severity.Debug, captured.lines[0].first)
        assertEquals(Severity.Warn, captured.lines[1].first)
        assertTrue(captured.lines[1].second.startsWith("✗ process threw ("), captured.lines[1].second)
    }

    @Test
    fun `the ambient scope is exited even when the block throws`() {
        // Otherwise every later line in the process carries the prefix of the entry point that died —
        // worse than no prefix, because it attributes unrelated work to a failure.
        val scope = RecordingScope()

        assertFailsWith<IllegalStateException> {
            logger(Capturing()).invocation(scope, "process") { error("boom") }
        }

        assertEquals(listOf(true), scope.exited)
    }

    @Test
    fun `a nested call exits with the ownership its own enter reported`() {
        // `enter` answers whether THIS call owns the scope; an inner call that did not own it must not
        // clear the outer call's prefix on the way out.
        val inner = RecordingScope(owns = false)

        logger(Capturing()).invocation(inner, "inner") { }

        assertEquals(listOf(false), inner.exited)
    }
}

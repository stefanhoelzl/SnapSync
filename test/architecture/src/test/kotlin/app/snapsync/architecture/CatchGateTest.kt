package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The catch gate** (`docs/architecture.md`; law `docs/architecture.md`, "Catch sites keep
 * cancellation").
 *
 * `runCatching` and `catch (… : Throwable | Exception)` catch `CancellationException` — the way a cancelled
 * coroutine unwinds. Around a suspend call that turns "my caller gave up" into a failure value: a cancelled join
 * reported FAILED, a cancelled leave step posted an Error-severity crash event, and structured concurrency lost
 * the signal it runs on. About twenty sites did exactly that.
 *
 * So production source (under `domain/`, `adapter/`, `app/`, `ui/`, outside test source sets) may not call
 * `runCatching`, and may catch `Throwable`/`Exception` only where the SAME `try` catches `CancellationException`
 * first, or where the handler rethrows what it caught (log-and-rethrow). Everything else goes through
 * `runCatchingCancellable` (`model/`) or `Logger.bestEffort` (`ports/`).
 *
 * The allowlist is the helpers themselves and the ObjC boundaries, where NOTHING may escape — a Kotlin throwable,
 * cancellation included, aborts the process there.
 *
 * **Text, not a type check, and it says so:** it cannot see a catch of a `typealias` for `Throwable`, and it
 * trusts that a `catch (e: CancellationException)` clause above a broad one in the same `try` rethrows.
 */
class CatchGateTest {

    /** Where a catch-all is the point, and why. Keyed by path suffix. */
    private val allowed = mapOf(
        "/domain/model/src/commonMain/kotlin/app/snapsync/model/Catching.kt" to
            "the cancellation-keeping helpers themselves",
        "/domain/services/src/commonMain/kotlin/app/snapsync/services/upload/ProcessCycle.kt" to
            "runProcessCycle: the extension's ObjC boundary — any throwable, cancellation included, aborts the process",
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/objc/ObjCBoundary.kt" to
            "objcBoundary: every Kotlin block and delegate method ObjC calls — nothing may unwind into ObjC frames",
    )

    private val productionRoots = listOf("/domain/", "/adapter/", "/app/", "/ui/")

    private fun isProduction(path: String): Boolean =
        productionRoots.any { path.startsWith(it) } && !Regex("""/src/(\w*[Tt]est)/""").containsMatchIn(path)

    /** Every offending catch site in [code] (comments already stripped). */
    internal fun offenders(code: String): List<String> {
        val out = mutableListOf<String>()
        fun line(at: Int) = code.take(at).count { it == '\n' } + 1
        Regex("""(?<![\w.])runCatching\s*\{|\.runCatching\s*\{""").findAll(code).forEach {
            out += "line ${line(it.range.first)}: runCatching"
        }
        Regex("""catch\s*\(\s*(\w+)\s*:\s*(?:kotlin\.)?(Throwable|Exception)\s*\)\s*\{""").findAll(code).forEach { m ->
            val variable = m.groupValues[1]
            // The try this catch belongs to: text since the nearest preceding `try {`.
            val tryStart = code.lastIndexOf("try {", m.range.first).coerceAtLeast(0)
            val precededByCancellation = Regex("""catch\s*\(\s*\w+\s*:\s*[\w.]*CancellationException\s*\)""")
                .containsMatchIn(code.substring(tryStart, m.range.first))
            val body = blockBody(code, m.range.last)
            val rethrows = Regex("""\bthrow\s+$variable\b""").containsMatchIn(body)
            if (!precededByCancellation && !rethrows) out += "line ${line(m.range.first)}: catch ($variable: ${m.groupValues[2]})"
        }
        return out
    }

    /** The text of the block whose `{` is at [open]. */
    private fun blockBody(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return code.substring(open + 1, i) }
            }
        }
        return code.substring(open + 1)
    }

    @Test
    fun `production catch sites keep cancellation`() {
        val files = SourceScan.kotlinFiles().filter { isProduction(it.path) }
        assertTrue(files.size >= 200, "the catch gate scanned only ${files.size} production files — the scope is broken")
        val found = files
            .filterNot { f -> allowed.keys.any { f.path.endsWith(it) } }
            .flatMap { f -> offenders(ZoneGates.stripComments(f.text)).map { "  ${f.path} $it" } }
        assertTrue(
            found.isEmpty(),
            "these catch sites can swallow cancellation (law \"Catch sites keep cancellation\"). Use " +
                "`runCatchingCancellable` (model/) or `Logger.bestEffort` (ports/); a handler that must catch " +
                "broadly either catches `CancellationException` first in the same `try` and rethrows it, or " +
                "rethrows what it caught. Only an ObjC boundary may catch everything, and it is named in this gate.\n" +
                found.joinToString("\n"),
        )
    }

    @Test
    fun `the allowlist names only files that still need it`() {
        allowed.keys.forEach { suffix ->
            val file = SourceScan.kotlinFiles().firstOrNull { it.path.endsWith(suffix) }
            assertTrue(file != null, "catch gate allowlist: $suffix no longer exists — drop the entry")
        }
    }

    @Test
    fun `the scan tells a kept cancellation from a swallowed one`() {
        val sample = """
            fun a() = runCatching { work() }
            fun b() = x.runCatching { work() }
            fun c() = runCatchingCancellable { work() }
            fun d() { try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { log(e) } }
            fun e() { try { work() } catch (t: Throwable) { log(t); throw t } }
            fun f() { try { work() } catch (e: Exception) { log(e) } }
        """.trimIndent()
        assertEquals(
            listOf("line 1: runCatching", "line 2: runCatching", "line 6: catch (e: Exception)"),
            offenders(sample),
        )
    }
}

package app.snapsync.journeys.run

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * The real backend, on the loopback address the `local` deployment bakes into the build (127.0.0.1:8080), its store
 * the machine's own and fresh per run: `deno task dev:local` in [api]. `dev:local` rather than `serve.ts --ephemeral`,
 * which exits when its stdin closes. Its output, a line per request, goes to [log].
 */
class LocalBackend(private val api: File, private val log: File) : AutoCloseable {
    private var process: Process? = null

    fun start() {
        process = ProcessBuilder("deno", "task", "dev:local").directory(api)
            .redirectErrorStream(true).redirectOutput(log).start()
    }

    /**
     * Waits, [within], until the backend answers a request — any answer: its first start downloads its dependencies —
     * which also warms it, because a cold deno exceeds the app's 5 s HTTP timeout. The backend tells nothing when it is
     * up, so this is a read of it repeated until the bound, failing with the tail of its log.
     */
    fun awaitAnswering(within: Duration) {
        val http = HttpClient.newBuilder().connectTimeout(PROBE.toJavaDuration()).build()
        val probe = HttpRequest.newBuilder(URI("$BASE/events/$NO_EVENT")).timeout(PROBE.toJavaDuration()).build()
        val giveUp = TimeSource.Monotonic.markNow() + within
        while (true) {
            if (runCatching { http.send(probe, HttpResponse.BodyHandlers.discarding()) }.isSuccess) return
            check(process?.isAlive == true) { "the local backend exited: ${tail()}" }
            check(!giveUp.hasPassedNow()) { "the local backend never answered on $BASE within $within: ${tail()}" }
            Thread.sleep(RETRY_AFTER.inWholeMilliseconds)
        }
    }

    private fun tail(): String = log.takeIf { it.exists() }?.readLines()?.takeLast(TAIL)?.joinToString("\n").orEmpty()

    /** Stops `deno task` and the server it spawned. */
    override fun close() {
        val running = process ?: return
        running.descendants().forEach { it.destroy() }
        running.destroy()
        if (!running.waitFor(STOP_WITHIN.inWholeSeconds, TimeUnit.SECONDS)) {
            running.descendants().forEach { it.destroyForcibly() }
            running.destroyForcibly()
        }
    }

    companion object {
        /** The local deployment's API base, as the app baked for it calls it. */
        const val BASE = "http://127.0.0.1:8080/api/v2"
        const val PORT = 8080
        private const val NO_EVENT = "00000000-0000-4000-8000-000000000000"
        private val PROBE = 5.seconds
        private val RETRY_AFTER = 2.seconds
        private val STOP_WITHIN = 10.seconds
        private const val TAIL = 40
    }
}

package app.snapsync.liveedge

import app.snapsync.contracts.ClientIdentity
import app.snapsync.contracts.EdgeSetup
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GateRecorder
import app.snapsync.contracts.Seeded
import app.snapsync.http.withCredentialInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking

/**
 * The REAL backend, served locally for the backend port contracts' `Live` bindings (capability
 * `port-contracts`; the choice and its costs: `changes/archive/2026-09-23-contract-backend-clients`).
 *
 * One `deno` process per test JVM runs `api/src/dev/serve.ts --ephemeral` — the production `createApp` over a
 * filesystem store and SQLite, on a loopback port Deno picks. "A fresh instance per clause" is a fresh client,
 * a freshly created event and fresh device ids, so the process itself is shared: nothing one clause does is
 * visible at another clause's addresses.
 *
 * ZONE SAFETY IS STRUCTURAL. The process is granted `--allow-net=127.0.0.1` and nothing wider, so a code path
 * that reached for bunny fails `NotCapable` instead of touching the `snap-sync-dev` zone real users share;
 * `serve.ts` independently refuses any deployment whose storage is not the filesystem. Every event a clause
 * touches is one its binding created in this run.
 *
 * `deno` on PATH is a prerequisite of `./gradlew build`. Its absence FAILS every live clause with a message
 * naming it — it is never `Unreachable`, because a missing tool is not a state this host cannot reach, and
 * reading it as one would let a machine without Deno report the whole backend `NotRunHere`.
 *
 * Depends on nothing in `:test:world`: this fixture is the backend that outlives the mini-edge. It lives in
 * `:test:edge` so that both of its consumers — the backend contracts' live bindings and the world's
 * real-backend option — stand on the same process lifecycle rather than two copies of it.
 */
object LiveEdge {

    private val apiDir: File = File(
        System.getProperty("snapsync.apiDir")
            ?: error("snapsync.apiDir is not set — run through Gradle (the consumer contract: test/edge/build.gradle.kts)"),
    )

    private val storeRoot: File = File(System.getProperty("snapsync.liveEdgeStore") ?: "build/live-edge")

    /** The v2 device API base of the running edge, started on first use. */
    val base: String by lazy { start() + "/api/v2" }

    /** A client for [EdgeSetup]: no interceptor, because setup is not under contract. */
    fun setup(): Pair<EdgeSetup, HttpClient> = HttpClient(CIO).let { EdgeSetup(it, base) to it }

    /**
     * The client the app ships, over a real socket: the production interceptor, declaring [identity], with the
     * gate's callbacks recorded into [gate].
     */
    fun client(identity: ClientIdentity, gate: GateRecorder): HttpClient =
        HttpClient(CIO).withCredentialInterceptor(
            token = { identity.token },
            onRejected = gate::onRejected,
            appVersion = { identity.appVersion },
            onVersionRefused = gate::onVersionRefused,
        )

    /**
     * Enters one clause's state on the live edge through [seed], then builds the port under contract over the
     * client the state calls for. Setup is blocking because `Binding.create` is; the clause itself suspends.
     */
    fun <P> enter(
        seed: suspend (EdgeSetup) -> Seeded,
        port: (client: HttpClient, base: String, seeded: Seeded) -> P,
    ): Entered<EdgeSubject<P>> = runBlocking {
        val (setup, setupClient) = setup()
        val seeded = setupClient.use { seed(setup) }
        val gate = GateRecorder()
        val client = client(seeded.identity, gate)
        Entered.Ready(EdgeSubject(port(client, base, seeded), seeded, gate), dispose = { client.close() })
    }

    private fun start(): String {
        storeRoot.mkdirs()
        val store = File(storeRoot, "store-${ProcessHandle.current().pid()}").apply { deleteRecursively() }
        val command = listOf(
            "deno", "run", "--no-prompt",
            "--allow-net=127.0.0.1",
            "--allow-read=${apiDir.absolutePath},${store.absolutePath}",
            "--allow-write=${store.absolutePath}",
            "src/dev/serve.ts", "--ephemeral", "--store=${store.absolutePath}",
        )
        val process = try {
            ProcessBuilder(command).directory(apiDir).start()
        } catch (e: IOException) {
            throw IllegalStateException(
                "the backend contracts' live binding needs `deno` on PATH — `./gradlew build` requires it " +
                    "(CLAUDE.md, Build & test). Could not start it: ${e.message}",
                e,
            )
        }
        // stdin stays OPEN for this JVM's lifetime: `--ephemeral` exits on its EOF, so a JVM killed without
        // running the hook below still takes the server with it.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { process.outputStream.close() }
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                store.deleteRecursively()
            },
        )
        val stderr = StringBuffer()
        Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            .apply { isDaemon = true }.start()
        val ready = CompletableFuture<String>()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                READY.matchEntire(line.trim())?.let { ready.complete(it.groupValues[1]) }
            }
            ready.completeExceptionally(IllegalStateException("the live edge exited before it was ready"))
        }.apply { isDaemon = true }.start()
        return try {
            ready.get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            process.destroyForcibly()
            val why = if (e is TimeoutException) "was not ready within ${READY_TIMEOUT_SECONDS}s" else "failed to start"
            throw IllegalStateException("the live edge $why. Its stderr:\n$stderr", e)
        }
    }

    private val READY = Regex("""LIVE-EDGE READY (\S+)""")

    /** Generous: a cold CI runner resolves and caches the backend's module graph on the first start. */
    private const val READY_TIMEOUT_SECONDS = 180L
}

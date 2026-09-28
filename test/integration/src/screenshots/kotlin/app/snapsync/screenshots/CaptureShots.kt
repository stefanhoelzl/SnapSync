package app.snapsync.screenshots

import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.integration.Rig
import app.snapsync.integration.Shot
import app.snapsync.integration.reach
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The marketing screenshots, captured from the REAL app (`docs/deployment.md`, "Screenshots"): the rig build on a booted
 * simulator, over launch adapters that mock every system but the screen and the app's foreground life, driven to each
 * [Shot] by the same scenario `ShotsTest` runs on the JVM host, and captured light and dark with `simctl io screenshot`.
 *
 *     CaptureShots <simulator udid> <rig port> <out dir>
 *
 * Run by `screenshots.yml` only, on a macOS runner, with the app installed and the status bar already overridden. It
 * exits non-zero, naming why, on anything short of all six captures.
 */
fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println("usage: CaptureShots <simulator udid> <rig port> <out dir>")
        exitProcess(2)
    }
    val (udid, port, out) = args
    runBlocking { Capture(Simulator(udid, port.toInt()), File(out).apply { mkdirs() }).all() }
}

private class Capture(private val sim: Simulator, private val out: File) {
    private val rig = Rig(RigClient("http://127.0.0.1:${sim.port}"))

    suspend fun all() {
        // The first launch runs whatever choice the App Group holds — none, on a fresh install: all real. Writing the
        // choice exits the app; every later launch composes over it.
        sim.launch()
        rig.device("adapters", body = CHOICE)
        sim.awaitGone()
        sim.launch()
        val folder = File(rig.deviceJson("adapters/current").getValue("folder").jsonPrimitive.content)
        Shot.entries.forEach { shot -> capture(shot, folder) }
    }

    /** From an empty world: the mocked systems' saved state deleted before a launch, so nothing a shot before left. */
    private suspend fun capture(shot: Shot, folder: File) {
        sim.terminate()
        File(folder, "state").deleteRecursively()
        File(folder, "databases").deleteRecursively()
        sim.launch()
        rig.reach(shot) { relaunchOnceSaved(folder) }

        sim.appearance("light")
        val light = sim.settledScreenshot(File(out, "${shot.id}-light.png"))
        sim.appearance("dark")
        val dark = sim.settledScreenshot(File(out, "${shot.id}-dark.png"))
        // The app re-themes on the trait change; identical frames mean it did not, and half the set would be mislabelled.
        check(!light.readBytes().contentEquals(dark.readBytes())) { "${shot.id} did not re-theme on the appearance flip" }
        println("captured ${shot.id} (light + dark)")
    }

    /**
     * The next launch must find the clock the shot set: the app writes a changed mocked system every 500 ms, and a
     * terminate is no exit it can save before. So wait until the clock's state carries it, then terminate and launch.
     */
    private suspend fun Rig.relaunchOnceSaved(folder: File) {
        val clock = File(folder, "state/clock.json")
        val millis = Instant.parse(Shot.NOW).toEpochMilliseconds().toString()
        within(10.seconds, "the clock's state was never saved to $clock") { clock.isFile && millis in clock.readText() }
        sim.terminate()
        sim.launch()
    }

    private suspend fun Rig.deviceJson(name: String) =
        Json.parseToJsonElement(client.deviceVerb(name).done()).jsonObject

    private companion object {
        /**
         * Every system mocked but two. The SCREEN stays real because a mocked screen renders nothing — the capture is
         * of the real Compose scene. The LIFECYCLE stays real so the app's own foreground drives it, as on a phone.
         */
        val CHOICE = """
            backend=mock
            library=mock
            files=mock
            databases=mock
            preferences=mock
            keychain=mock
            integrity=mock
            crash-reporter=mock
            process-info=mock
            clock=mock
            wake=mock
            background-time=mock
            extension-registry=mock
            upload-queue=mock
            upload-session=mock
            downloads=mock
            lifecycle=real
            links=mock
            push=mock
            screen=real
            system-ui=mock
        """.trimIndent()
    }
}

/** The booted simulator the app is installed on, and the `simctl` calls a capture makes. */
private class Simulator(private val udid: String, val port: Int) {

    /** Launch the app with its rig port, and wait for the channel to answer. */
    suspend fun launch() {
        simctl("launch", udid, BUNDLE, env = mapOf("SIMCTL_CHILD_SNAPSYNC_RIG_PORT" to port.toString()))
        within(90.seconds, "the app never answered on its rig port $port") { healthy() }
    }

    /** Terminate the app and wait until it is gone: it ignores SIGTERM, and a launch over a live one renders black. */
    suspend fun terminate() {
        runCatching { simctl("terminate", udid, BUNDLE) }
        awaitGone()
    }

    suspend fun awaitGone() {
        within(20.seconds, "the app was still running") { BUNDLE !in simctl("spawn", udid, "launchctl", "list") }
    }

    fun appearance(mode: String) {
        simctl("ui", udid, "appearance", mode)
    }

    /**
     * Capture until two consecutive frames are byte-identical, and keep that frame at [to]. `simctl` stamps no time into
     * a PNG, so identical bytes mean the pixels stopped moving — Compose has settled and any theme change has landed.
     */
    suspend fun settledScreenshot(to: File): File {
        val previous = File(to.parentFile, ".prev.png")
        simctl("io", udid, "screenshot", previous.path)
        repeat(SETTLE_ATTEMPTS) {
            delay(1.seconds)
            simctl("io", udid, "screenshot", to.path)
            if (to.readBytes().contentEquals(previous.readBytes())) {
                previous.delete()
                return to
            }
            to.copyTo(previous, overwrite = true)
        }
        error("the screen never settled for ${to.name}")
    }

    private fun healthy(): Boolean = runCatching {
        (java.net.URI("http://127.0.0.1:$port/health").toURL().openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 1000
            readTimeout = 1000
            responseCode == 200
        }
    }.getOrDefault(false)

    private fun simctl(vararg args: String, env: Map<String, String> = emptyMap()): String {
        val process = ProcessBuilder(listOf("xcrun", "simctl") + args).redirectErrorStream(true)
            .apply { environment().putAll(env) }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "simctl ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private companion object {
        const val BUNDLE = "app.snapsync"
        const val SETTLE_ATTEMPTS = 25
    }
}

/** Poll [ready] until it holds, failing with [what] after [timeout]. */
private suspend fun within(timeout: Duration, what: String, ready: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!ready()) {
        check(deadline.hasNotPassedNow()) { "$what within $timeout" }
        delay(POLL)
    }
}

private val POLL = 250.milliseconds

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
 * iOS simulator or Android emulator, over launch adapters that mock every system but the screen and the app's foreground
 * life, driven to each [Shot] by the same scenario `ShotsTest` runs on the JVM host, and captured light and dark.
 *
 *     CaptureShots ios     <simulator udid> <rig port> <out dir>
 *     CaptureShots android <adb serial>     <rig port> <out dir>
 *
 * Run by `screenshots.yml` only — on a macOS runner with the app installed and the status bar already overridden, or on
 * a Linux runner with the debug rig build installed on the emulator. It exits non-zero, naming why, on anything short of
 * all six captures.
 */
fun main(args: Array<String>) {
    if (args.size != 4 || args[0] !in setOf("ios", "android")) {
        System.err.println("usage: CaptureShots ios|android <simulator udid | adb serial> <rig port> <out dir>")
        exitProcess(2)
    }
    val (platform, id, port, out) = args
    val host = if (platform == "ios") Simulator(id, port.toInt()) else AndroidEmulator(id, port.toInt())
    runBlocking { Capture(host, File(out).apply { mkdirs() }).all() }
}

/** What a capture needs from the device the app runs on: its life, its appearance, its screen and the app's files. */
internal interface CaptureHost {
    /** The host port the app's control channel answers on. */
    val port: Int

    /** Launch the app, and wait for the channel to answer. */
    suspend fun launch()

    /** Stop the app and wait until it is gone. */
    suspend fun terminate()

    suspend fun awaitGone()

    fun appearance(dark: Boolean)

    /** One capture of the screen as PNG, at [to]. */
    fun screenshot(to: File)

    /** The text of the app's file at [path], or null if there is none. */
    fun read(path: String): String?

    /** Delete the app's file or directory at [path], if any. */
    fun delete(path: String)

    /**
     * Capture until two consecutive frames are byte-identical, and keep that frame at [to]. Neither platform stamps a
     * time into its PNG, so identical bytes mean the pixels stopped moving — Compose has settled and any theme change
     * has landed.
     */
    suspend fun settledScreenshot(to: File): File {
        val previous = File(to.parentFile, ".prev.png")
        screenshot(previous)
        repeat(SETTLE_ATTEMPTS) {
            delay(1.seconds)
            screenshot(to)
            if (to.readBytes().contentEquals(previous.readBytes())) {
                previous.delete()
                return to
            }
            to.copyTo(previous, overwrite = true)
        }
        error("the screen never settled for ${to.name}")
    }

    /** Whether the app's control channel answers on [port]. */
    fun healthy(): Boolean = runCatching {
        (java.net.URI("http://127.0.0.1:$port/health").toURL().openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 1000
            readTimeout = 1000
            responseCode == 200
        }
    }.getOrDefault(false)
}

private class Capture(private val host: CaptureHost, private val out: File) {
    private val rig = Rig(RigClient("http://127.0.0.1:${host.port}"))

    suspend fun all() {
        // The first launch runs whatever choice the app's shared area holds — none, on a fresh install: on iOS all real,
        // on Android all mocked in memory. Writing the choice exits the app; every later launch composes over it.
        host.launch()
        rig.device("adapters", body = CHOICE)
        host.awaitGone()
        host.launch()
        val folder = rig.deviceJson("adapters/current").getValue("folder").jsonPrimitive.content
        Shot.entries.forEach { shot -> capture(shot, folder) }
    }

    /** From an empty world: the mocked systems' saved state deleted before a launch, so nothing a shot before left. */
    private suspend fun capture(shot: Shot, folder: String) {
        host.terminate()
        host.delete("$folder/state")
        host.delete("$folder/databases")
        host.launch()
        rig.reach(shot) { relaunchOnceSaved(folder) }

        host.appearance(dark = false)
        val light = host.settledScreenshot(File(out, "${shot.id}-light.png"))
        host.appearance(dark = true)
        val dark = host.settledScreenshot(File(out, "${shot.id}-dark.png"))
        // The app re-themes on the appearance change; identical frames mean it did not, and half the set would be
        // mislabelled.
        check(!light.readBytes().contentEquals(dark.readBytes())) { "${shot.id} did not re-theme on the appearance flip" }
        println("captured ${shot.id} (light + dark)")
    }

    /**
     * The next launch must find the clock the shot set: the app writes a changed mocked system every 500 ms, and a
     * terminate is no exit it can save before. So wait until the clock's state carries it, then terminate and launch.
     */
    private suspend fun Rig.relaunchOnceSaved(folder: String) {
        val clock = "$folder/state/clock.json"
        val millis = Instant.parse(Shot.NOW).toEpochMilliseconds().toString()
        within(10.seconds, "the clock's state was never saved to $clock") { host.read(clock)?.contains(millis) == true }
        host.terminate()
        host.launch()
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

/** Poll [ready] until it holds, failing with [what] after [timeout]. */
internal suspend fun within(timeout: Duration, what: String, ready: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!ready()) {
        check(deadline.hasNotPassedNow()) { "$what within $timeout" }
        delay(POLL)
    }
}

/** Run [command], failing with its output unless it exits 0; its output (stdout and stderr) otherwise. */
internal fun run(command: List<String>, env: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    return output
}

private val POLL = 250.milliseconds
private const val SETTLE_ATTEMPTS = 25

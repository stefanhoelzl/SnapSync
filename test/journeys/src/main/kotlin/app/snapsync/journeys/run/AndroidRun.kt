package app.snapsync.journeys.run

import app.snapsync.client.RigClient
import app.snapsync.journeys.device.AndroidDriver
import app.snapsync.levers.AdaptersLevers
import app.snapsync.levers.CurrentAdapters
import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.RigLevers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The all-real journeys on an Android emulator — `journeys (android)`, and its reproduction on this box (the
 * `snapsync-android` skill boots the emulator first; CI's `android-emulator` action boots it, so the run expects it
 * booted). The rig RELEASE build — R8 and resource shrinking as the store build runs them — so every journey exercises R8
 * output: a class R8 removed or renamed that is reached only by name breaks here, not in the store. Built before, by
 * `scripts/journeys-build android`; this run builds nothing.
 *
 * It starts the real backend, installs the rig build, grants it the photo library BEFORE its first launch, and chooses
 * its adapters: real for every system Android has an adapter for, mock for the rest (the crash reporter, and the iOS-only
 * upload-job queue and extension registration). Then the journeys, over the control channel — the same ones the iOS
 * simulator app runs — and, last, the force-stop journey, which only Android can play.
 *
 * It FAILS on: a backend that never answers, an app baked for another backend, a vocabulary entry the host leaves
 * unclassified, a refused adapter choice, and any journey failure.
 */
class AndroidRun(
    private val device: AndroidDriver,
    private val apk: File,
    private val api: File,
    private val evidence: Evidence,
    private val runner: JourneyRunner,
) {
    private val client = RigClient("http://127.0.0.1:$RIG_PORT")

    /** Answers whether every journey passed; throws on what stopped the run before them. */
    fun run(): Boolean = LocalBackend(api, evidence.file("backend.log")).use { backend ->
        try {
            runBlocking { journeys(backend) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            device.screenshot(evidence.file("failure.png"))
            throw e
        } finally {
            evidence.file("logcat.txt").writeText(device.log())
            device.removeForward(RIG_PORT)
            client.close()
        }
    }

    private suspend fun journeys(backend: LocalBackend): Boolean {
        // Started first, so its first start (dependency downloads included) overlaps the install.
        evidence.stage("backend: starting")
        backend.start()

        check(device.booted()) { "the device ${device.serial} has not finished booting" }
        device.awaitBroadcastsIdle()
        evidence.stage("device: booted, broadcasts idle")
        device.clearLog()
        device.uninstall(APP)
        device.install(apk)
        // The photo library under a full grant, before the first launch: the journeys seed it and upload from it.
        GRANTS.forEach { device.grant(APP, it) }
        device.reverse(LocalBackend.PORT)
        device.forward(RIG_PORT, RIG_PORT)
        evidence.stage("app: installed and granted")

        backend.awaitAnswering(BACKEND_START)
        evidence.stage("backend: answering")

        launch()
        evidence.stage("app: launched (every system mocked)")
        // Written while not joined; the app exits on it, and its next start composes over the choice.
        val written = client.levers(AdaptersLevers).choose(CHOICE)
        evidence.file("adapters.answer").writeText("$written\n")
        awaitExited()
        launch()
        val current: CurrentAdapters = client.levers(AdaptersLevers).current()
        evidence.file("adapters.current").writeText("$current\n")
        evidence.stage("app: relaunched over the chosen adapters")

        checkBakedFor(LocalBackend.BASE)
        checkAdvertisement()

        evidence.stage("journeys: starting")
        val passed = runner.run(
            listOf(JOURNEYS, FORCE_STOP_JOURNEY),
            mapOf(
                APP_A to "http://127.0.0.1:$RIG_PORT",
                BACKEND to LocalBackend.BASE,
                ANDROID_SERIAL to device.serial,
                ADB to device.adb,
            ),
        )
        evidence.stage("journeys: done")
        return passed
    }

    /** Starts the app and waits for its control channel to answer. */
    private suspend fun launch() {
        device.start(ACTIVITY)
        client.awaitChannel(CHANNEL_UP)
    }

    private suspend fun awaitExited() = until(
        EXIT,
        "the app did not exit after the adapter choice",
    ) { !device.running(APP) }

    /**
     * Never drive an app baked for another backend: that is the shared production one, and these journeys create events
     * and upload photos.
     */
    private suspend fun checkBakedFor(backend: String) {
        val base = client.state().build["uploadBase"]
        check(base == backend) { "the app is baked for '$base', not the local backend $backend — refusing to run" }
    }

    /** The host's classification of the control vocabulary, read once: an unclassified entry answers only when asked. */
    private suspend fun checkAdvertisement() {
        val ad = client.levers(RigLevers).advertise()
        evidence.file("device.json").writeText(Json.encodeToString(DeviceAdvertisement.serializer(), ad))
        val gaps = ad.unclassified + ad.outsideVocabulary
        check(gaps.isEmpty()) { "the app host leaves vocabulary entries unaccounted: $gaps" }
    }

    companion object {
        const val APP = "app.snapsync"
        const val ACTIVITY = "app.snapsync/app.snapsync.android.MainActivity"
        const val RIG_PORT = 18099
        val GRANTS = listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "ACCESS_MEDIA_LOCATION")

        /**
         * Real wherever Android has an adapter; every other system named `mock` (an omitted one reads as real, and a
         * choice leaving a system real that has no Android adapter is refused).
         */
        val CHOICE = (
            listOf(
                "screen", "lifecycle", "clock", "files", "databases", "preferences", "keychain", "integrity", "backend",
                "links", "library", "system-ui", "wake", "background-time", "upload-session", "downloads", "push",
                "process-info",
            ).map { "$it=real" } + listOf("crash-reporter", "extension-registry", "upload-queue").map { "$it=mock" }
            ).joinToString("\n")

        private val BACKEND_START = 3.minutes

        /** How long a started app takes to serve its control channel: a cold start on a loaded runner takes a minute. */
        val CHANNEL_UP = 2.minutes
        private val EXIT = 30.seconds
    }
}

/**
 * Waits for the app's control channel to answer, after a start: it tells nothing until it does, so its health is read
 * until it answers, or [within] passed.
 */
suspend fun RigClient.awaitChannel(within: Duration) =
    until(within, "the app's control channel never answered") { runCatching { health() }.isSuccess }

/** Repeats [holds] — a read of a system that tells nothing when it changes — until it holds, or fails after [within]. */
suspend fun until(within: Duration, failure: String, holds: suspend () -> Boolean) {
    val giveUp = TimeSource.Monotonic.markNow() + within
    while (!holds()) {
        check(!giveUp.hasPassedNow()) { "$failure (within $within)" }
        delay(RETRY_EVERY)
    }
}

private val RETRY_EVERY = 1.seconds

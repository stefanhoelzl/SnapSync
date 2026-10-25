package app.snapsync.journeys.run

import app.snapsync.client.RigClient
import app.snapsync.journeys.device.PHOTOS
import app.snapsync.journeys.device.PhotoMigrations
import app.snapsync.journeys.device.STOPPED_DAEMONS
import app.snapsync.journeys.device.Shell
import app.snapsync.journeys.device.SimulatorDriver
import app.snapsync.journeys.device.awaitPhotoMigration
import app.snapsync.levers.ContractLevers
import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.GalleryLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.SeedKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The simulator app's port contracts, then the all-real journeys on the same app — `journeys (ios)`, and its
 * reproduction on a Mac session (`docs/architecture.md`, "In-app hosts CI can reach are run live over the rig"). Built
 * before, by `scripts/journeys-build ios`: the rig app for the simulator, ad-hoc signed (an unsigned build has no App
 * Group), and this program; every Gradle and Kotlin daemon stopped, so nothing overlaps the run.
 *
 * ONE simulator, never two: a second freshly created simulator's first-boot work swamped the hosted runner (3 vCPU,
 * 7 GB). Measured, it tripled the job and made the journeys flaky (decision record:
 * `openspec/changes/archive/2026-09-25-one-simulator-journeys`). On CI it is the runner image's own iPhone 17
 * ([udid], restored settled from the cache); anywhere else one is created and deleted after, because a reused one keeps
 * the photos earlier runs seeded. Photo access is granted with `applesimutils` BEFORE the first launch. A fresh
 * simulator's photo library is not writable when it reports booted, so the app's first write is its own `photo library:
 * ready` stage — one BULK seed, dated 2001, outside every capture window — and then the library's BACKGROUND migration
 * is awaited on `assetsd`'s own completion event, because it holds every write while it runs.
 *
 * Then every contract the app registers under the full grant; the journeys, against the app and the local backend;
 * and, one launch per grant, the contracts under each grant short of full (a partial selection, a refusal, never
 * asked), those that must run in the background behind Settings. It FAILS on: any outcome that fails a run —
 * `Failed(`, `Diverged(`, `NotWithin(` (`docs/architecture.md`, "Outcomes are explicit and none is silent") — any
 * refusal, an empty registry, an app that never publishes its rig port or is baked for another backend, an
 * incomplete advertisement, a backend or fixture that never answers, and any journey failure.
 */
class IosRun(
    private val simulators: SimulatorDriver,
    private val shell: Shell,
    private val app: File,
    private val udid: String?,
    private val api: File,
    private val evidence: Evidence,
    private val runner: JourneyRunner,
) {
    private val started = Instant.now()
    private val rigPort = freePort()
    private val client = RigClient("http://127.0.0.1:$rigPort")

    fun run(): Boolean {
        val device = udid ?: simulators.create("contracts-${ProcessHandle.current().pid()}")
        return try {
            LocalBackend(api, evidence.file("backend.log")).use { backend ->
                Fixture(evidence.file("transfer-fixture.log")).use { fixture ->
                    runBlocking { contractsAndJourneys(device, backend, fixture) }
                }
            }
        } finally {
            client.close()
            evidence(device)
            simulators.shutdown(device)
            if (udid == null) simulators.delete(device)
        }
    }

    private suspend fun contractsAndJourneys(device: String, backend: LocalBackend, fixture: Fixture): Boolean {
        evidence.stage("backend: starting")
        backend.start()
        backend.awaitAnswering(BACKEND_START)
        evidence.stage("backend: answering")

        evidence.stage("simulator: booting")
        simulators.boot(device)
        evidence.stage("simulator: booted")
        // On CI the cached device already holds them disabled, and this is a no-op.
        val loaded = simulators.stopDaemons(device, STOPPED_DAEMONS)
        evidence.file("daemons-stop.log").writeText("still loaded after the stop: $loaded\n")
        // The app's first photo write waits until `assetsd` has built the library's compute cache and opened its search
        // index (74 s of `validate` in run 36181223846; 1:23–4:43 per run before this). Opening Photos now, while the app
        // installs, starts that preparation earlier: the first write then took 1–46 s (run 36188792139).
        simulators.launch(device, PHOTOS)
        simulators.install(device, app)
        evidence.stage("app: installed")
        simulators.grantPhotos(device, BUNDLE, "YES")
        evidence.stage("app: granted")
        // The loopback peer the transfer contracts exchange bytes with, started BEFORE the app and asked to answer, so a
        // fixture that is not up fails here by name rather than as every transport clause's transfer error.
        fixture.start()

        launch(device)
        evidence.stage("app: launched")
        checkBakedFor(LocalBackend.BASE)
        checkAdvertisement()

        // The photo library's first-use wait, as its own stage: the app's first write.
        client.levers(GalleryLevers).seed(1, SeedKind.BULK)
        evidence.stage("photo library: ready")
        // ...and its BACKGROUND migration, which `dasd` may start after that first write returned and which then holds
        // every write for minutes (96–184 s measured): a clause writing meanwhile fails past its bound (runs 36868431564,
        // 37004211450).
        val migration = awaitPhotoMigration(shell, device, evidence.file("photo-migration.log"), MIGRATION)
        check(migration.state == PhotoMigrations.State.SETTLED) {
            "the photo library's background migration did not settle: ${migration.why}"
        }
        evidence.stage("photo library: migrated")

        evidence.stage("contracts: starting")
        val contracts = Contracts(fixture.url)
        contracts.runAll(grant = null)
        evidence.result("journeys: every registered contract passed")

        // A failure is read first as a contract clause nobody wrote.
        evidence.stage("journeys: starting")
        val passed = runner.run(
            listOf(JOURNEYS),
            mapOf(APP_A to "http://127.0.0.1:$rigPort", BACKEND to LocalBackend.BASE),
        )
        evidence.stage("journeys: done")
        copyAppLog(device, "debug.log")
        if (!passed) return false

        // The grants short of full: one launch holds one grant, so the app is stopped, granted otherwise and relaunched
        // for each. A refused or partial grant is already decided, so nothing they run asks the member anything; a
        // never-asked launch runs only reads.
        for ((access, grant) in SHORT_OF_FULL) {
            simulators.terminate(device, BUNDLE)
            simulators.grantPhotos(device, BUNDLE, access)
            launch(device)
            evidence.stage("app: relaunched under $grant")
            contracts.runAll(grant)
            contracts.runInBackground(device, grant)
        }
        evidence.result("journeys: every registered contract passed under every grant, and the journeys passed")
        return true
    }

    /**
     * Launches the app with its rig port, and waits for it to publish that port: each simulator shares the Mac's
     * loopback, so a second instance on the default port fails to bind while a request reaches the FIRST one and
     * answers plausibly.
     */
    private suspend fun launch(device: String) {
        val published = File(simulators.dataContainer(device, BUNDLE), "Documents/rig.port")
        // The port a previous launch published would answer for a server that is not up yet.
        published.delete()
        simulators.launch(device, BUNDLE, mapOf("SNAPSYNC_RIG_PORT" to rigPort.toString()))
        try {
            until(PORT_PUBLISHED, "the app never published its rig port") { published.isFile }
        } catch (e: IllegalStateException) {
            // A pending system alert is visible only in a screenshot.
            simulators.screenshot(device, evidence.file("no-answer.png"))
            copyAppLog(device, "debug-no-answer.log")
            throw e
        }
        val bound = published.readText().trim()
        check(bound == rigPort.toString()) { "the app bound $bound, not $rigPort" }
        client.awaitChannel(AndroidRun.CHANNEL_UP)
    }

    /** Never drive an app baked for another backend: the shared production one would carry these runs' traffic. */
    private suspend fun checkBakedFor(backend: String) {
        val base = client.state().build["uploadBase"]
        check(base == backend) { "the app is baked for '$base', not the local backend $backend — refusing to run" }
    }

    /** The app host's classification of the control vocabulary, read once: nothing else checks it for completeness. */
    private suspend fun checkAdvertisement() {
        val ad = client.levers(RigLevers).advertise()
        evidence.file("device.json").writeText(Json.encodeToString(DeviceAdvertisement.serializer(), ad))
        println("advertisement: ${ad.host} ${ad.honoured.size} honoured, ${ad.refused.size} refused")
        val gaps = ad.unclassified + ad.outsideVocabulary
        check(gaps.isEmpty()) { "the app host leaves vocabulary entries unaccounted: $gaps" }
    }

    /** The contracts the app registers, run over the rig, each outcome table kept under its name. */
    private inner class Contracts(private val fixture: String) {
        private val service = client.levers(ContractLevers)

        /** Every contract the app lists for [grant] (`null`: the default, full one), failing on any refusal or bad outcome. */
        suspend fun runAll(grant: String?) {
            val tag = grant?.let { ".$it" }.orEmpty()
            val names = service.list(grant = grant)
            evidence.file("registry$tag").writeText(names.joinToString("\n", postfix = "\n"))
            check(
                names.isNotEmpty(),
            ) { "the simulator app registers no contract${grant?.let { " under grant $it" }.orEmpty()}" }
            val failed = names.filterNot { name ->
                // Bounded: a clause that never returns (a stalled import spins in the app) must fail this run, not hold
                // the macOS runner until the job's default six hours. Every contract takes seconds (~35 s for all).
                table("$name$tag") {
                    withTimeout(CONTRACT) { service.run(name, grant = grant, params = mapOf("fixture" to fixture)) }
                }
            }
            check(failed.isEmpty()) { "contracts did not pass${grant?.let { " under $it" }.orEmpty()}: $failed" }
        }

        /**
         * Every contract the app lists for [grant] that must run with the app in the BACKGROUND (`precondition =
         * IN_BACKGROUND`): each is posted, then Settings is launched over the app, and the entry — which waits for the
         * app to be behind another, holding background time — runs there. The app is brought back after each.
         */
        suspend fun runInBackground(device: String, grant: String) {
            val tag = ".$grant.IN_BACKGROUND"
            val names = service.list(grant = grant, precondition = IN_BACKGROUND)
            evidence.file("registry$tag").writeText(names.joinToString("\n", postfix = "\n"))
            val failed = names.filterNot { name ->
                coroutineScope {
                    val run = async { table("$name$tag") { service.run(name, grant = grant) } }
                    // A margin for the request to reach the app before Settings covers it: the entry waits for that.
                    delay(POSTED)
                    simulators.launch(device, SETTINGS)
                    run.await().also { simulators.launch(device, BUNDLE) }
                }
            }
            check(failed.isEmpty()) { "background contracts did not pass under $grant: $failed" }
        }

        /** Runs one contract, keeps its table as [file], prints it; answers whether every clause passed. */
        private suspend fun table(file: String, run: suspend () -> String): Boolean {
            val table = runCatching { run() }.getOrElse { "refused or failed: ${it.message ?: it::class.simpleName}" }
            evidence.file("$file.txt").writeText(table)
            println("=== $file\n$table")
            val bad = BAD_OUTCOMES.filter { it in table } + listOfNotNull("refused".takeIf { table.startsWith("refused") })
            if (bad.isNotEmpty()) println("  -> $bad")
            return bad.isEmpty()
        }
    }

    /** `scripts/transfer-fixture.py`, on a free loopback port: the peer the transfer contracts exchange bytes with. */
    private inner class Fixture(private val log: File) : AutoCloseable {
        private val port = freePort()
        val url = "http://127.0.0.1:$port"
        private var process: Process? = null

        suspend fun start() {
            process = ProcessBuilder("python3", "scripts/transfer-fixture.py", "--port", "$port", "--log", log.path)
                .redirectErrorStream(true).redirectOutput(evidence.file("transfer-fixture.out")).start()
            val http = HttpClient.newHttpClient()
            val health = HttpRequest.newBuilder(URI("$url/_health")).timeout(java.time.Duration.ofSeconds(2)).build()
            until(FIXTURE_UP, "the transfer fixture never answered on $port") {
                runCatching { http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK }.getOrDefault(
                    false,
                )
            }
        }

        override fun close() {
            process?.destroy()
        }
    }

    /** The app's own log, kept in the evidence as [name]. */
    private fun copyAppLog(device: String, name: String) {
        val log = File(simulators.dataContainer(device, BUNDLE), "Documents/debug.log")
        runCatching { log.copyTo(evidence.file(name), true) }
    }

    /**
     * This run's crash reports, host and simulator — a jetsam kill or a crash then reads apart from a freeze — and the
     * whole run's CPU time per process, beside the per-sample view in `resources.log`.
     */
    private fun evidence(device: String) {
        val crashes = evidence.file("crash/host").apply { mkdirs() }
        File(System.getProperty("user.home"), "Library/Logs/DiagnosticReports").walkTopDown()
            .filter { it.isFile && it.lastModified() >= started.toEpochMilli() }
            .forEach { runCatching { it.copyTo(File(crashes, it.name), true) } }
        File(System.getProperty("user.home"), "Library/Logs/CoreSimulator/$device").takeIf { it.isDirectory }
            ?.let { runCatching { it.copyRecursively(evidence.file("crash/simulator"), true) } }
        val cpu = shell.run(
            listOf("ps", "-A", "-o", "time=,pid=,comm="),
            30.seconds,
        ).out.lines().filter { it.isNotBlank() }
            .map { line -> cpuSeconds(line.trim().substringBefore(' ')) to line.trim().substringAfter(' ') }
            .sortedByDescending { it.first }.take(CPU_TOP)
        evidence.file("cpu-time.txt").writeText(cpu.joinToString("\n") { (s, rest) -> "%9.1f s %s".format(s, rest) })
        // The daemon stop held only if the simulator's media analysis never shows among the sampled processes.
        val sampled = evidence.file("resources.log").takeIf { it.exists() }?.readText().orEmpty()
        if (Regex("RuntimeRoot.*analysisd") in sampled) {
            evidence.result("journeys: warning — the simulator's media analysis ran despite the daemon stop")
        }
    }

    private fun cpuSeconds(time: String): Double = time.split(
        ':',
    ).fold(0.0) { s, part -> s * SIXTY + (part.toDoubleOrNull() ?: 0.0) }

    companion object {
        const val BUNDLE = "app.snapsync"
        private const val SETTINGS = "com.apple.Preferences"
        private const val IN_BACKGROUND = "IN_BACKGROUND"

        /** Each grant short of full: `applesimutils`'s word for it, and the contracts' name. */
        private val SHORT_OF_FULL = listOf("limited" to "LIMITED", "NO" to "DENIED", "unset" to "NOT_DETERMINED")
        private val BAD_OUTCOMES = listOf(" Failed(", " Diverged(", " NotWithin(")
        private val BACKEND_START = 3.minutes
        private val PORT_PUBLISHED = 3.minutes
        private val MIGRATION = 15.minutes
        private val CONTRACT = 5.minutes
        private val FIXTURE_UP = 30.seconds
        private val POSTED = 2.seconds
        private const val HTTP_OK = 200
        private const val CPU_TOP = 40
        private const val SIXTY = 60
    }
}

/** A loopback port no one holds now: each simulator shares the Mac's loopback, so a run must not take a fixed one. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

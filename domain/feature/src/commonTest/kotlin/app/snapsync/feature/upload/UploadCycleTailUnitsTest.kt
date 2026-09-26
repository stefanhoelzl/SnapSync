package app.snapsync.feature.upload

import app.snapsync.model.Candidate
import app.snapsync.model.LedgerState
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionRulesFor
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.CycleResult
import app.snapsync.model.PauseReason
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.UploadDiscovery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared cycle's two **tail units** (capability `sync-status`, "Each OS wake does its own work, then hands the
 * rest to one opportunistic tail"; `background-upload`, "The producer tops up from the ledger, not from the walk's
 * output"; decision record `changes/own-work-per-wake`, D1, D2, D6): the top-up ② creates from the ledger and never
 * walks; the walk ③ records and publishes and never creates; a stop abandons a walk before it writes anything, and
 * ends a top-up between two creations. The whole-cycle behaviour both share is `UploadCycleTest`'s.
 */
class UploadCycleTailUnitsTest {

    private class Library(var resources: List<Resource>, private val limit: Int = Int.MAX_VALUE) :
        BackgroundTransfer, UploadDiscovery {
        val created = mutableListOf<String>()
        var walks = 0
        var resolves = 0
        var platformReads = 0

        override suspend fun fetchRetryJobs(): List<PlatformUploadJob> = emptyList<PlatformUploadJob>().also { platformReads++ }
        override suspend fun drainTerminals(): List<PlatformUploadJob> = emptyList<PlatformUploadJob>().also { platformReads++ }
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) = Unit
        override suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome {
            if (created.size >= limit) return UploadCreateOutcome.LIMIT_EXCEEDED
            created += resource.filename
            return UploadCreateOutcome.CREATED
        }

        override suspend fun discover(policy: SelectionPolicy): Discovery {
            walks++
            val candidates: List<Candidate> = candidatesFromResources(resources)
            return Discovery(candidates, fullEnumeration = true)
        }

        override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
            resolves++
            return resources.filter { it.filename in keys }
        }
    }

    private class Provider : UploadRequestProvider {
        override suspend fun provide(resource: Resource) = UploadRequest("https://stub.invalid/x", emptyMap(), resource)
        override suspend fun provideForRetry(resource: Resource) = provide(resource)
    }

    private class Fixture(resources: List<Resource>, limit: Int = Int.MAX_VALUE) {
        val backend = InMemoryLedgerStore()
        val library = Library(resources, limit)
        var publishes = 0
        private val ledger = LedgerWriter(backend)

        suspend fun cycle(gate: CycleGate? = null, policy: SelectionPolicy? = null): UploadCycle {
            val admitting = policy ?: SelectionPolicy(
                selectionRulesFor(
                    includesUpload = true,
                    cutoff = captureCutoff("2026-01-01T00:00:00Z"),
                    ceiling = null,
                    suppressedAssetIds = { emptySet() },
                    albumExcludedAssetIds = { emptySet() },
                ),
            )
            val run = CycleGate.Run(
                UploadConfig(host = "https://edge.example", eventId = "E"),
                JoinedMembership(eventId = "E", policy = { admitting }, saveToAlbum = false, manifestVersion = 1),
            )
            return UploadCycle(
                readGate = { gate ?: run },
                engineFor = { SyncEngine(Provider(), ledger) },
                ledger = ledger,
                platform = library,
                library = library,
                onDiscovery = { _, _, _ -> publishes++; true },
                placeInAlbum = { _, _ -> },
            )
        }
    }

    private fun resource(name: String) = Resource(
        filename = name, assetId = name, contentType = "image/jpeg",
        metadata = mapOf(RESOURCE_META_CREATION_DATE to "2026-06-01T10:00:00Z"), data = Unit,
    )

    private val never: () -> Boolean = { false }

    @Test
    fun `a paused cycle touches nothing in any unit and asks to be invoked again`() = runTest {
        // The extension found the download store at an older schema than its own (capability `receiving-photos`): it
        // may not migrate it, and uploading without echo suppression would send downloaded photos back. Unlike a
        // withheld cycle it does not even read what the platform presented — the next run acknowledges it.
        val f = Fixture(listOf(resource("a")))
        val cycle = f.cycle(gate = CycleGate.Paused(PauseReason.OLD_SCHEMA))
        val paused = CycleResult.Paused(PauseReason.OLD_SCHEMA)

        assertEquals(paused, cycle.run())
        assertEquals(paused, cycle.topUp(never))
        assertEquals(WalkOutcome.Walked(paused, addedRows = false), cycle.walkAndPublish(never))
        assertEquals(0, f.library.platformReads, "nothing presented is read or acknowledged")
        assertEquals(0, f.library.walks, "the library is not walked")
        assertTrue(f.library.created.isEmpty(), "no job is created")
        assertEquals(0, f.publishes, "no manifest is published")
        assertEquals(null, f.backend.get("a"), "nothing is recorded")
    }

    @Test
    fun `the walk records and publishes but creates no job`() = runTest {
        val f = Fixture(listOf(resource("a"), resource("b")))
        val outcome = f.cycle().walkAndPublish(never)

        assertEquals(WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = true), outcome)
        assertEquals(LedgerState.DISCOVERED, f.backend.get("a")?.state)
        assertEquals(1, f.publishes, "the walk and the publish are one unit")
        assertTrue(f.library.created.isEmpty(), "creation is the top-up's")
    }

    @Test
    fun `the top-up after a walk creates from the walk's handles without resolving`() = runTest {
        val f = Fixture(listOf(resource("a"), resource("b")))
        val cycle = f.cycle()
        cycle.walkAndPublish(never)
        val result = cycle.topUp(never)

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a", "b"), f.library.created)
        assertEquals(0, f.library.resolves, "a row the walk just read is not resolved again")
        assertEquals(1, f.library.walks, "and the top-up walks nothing")
    }

    @Test
    fun `a top-up with no walk before it resolves from the ledger and walks nothing`() = runTest {
        val f = Fixture(listOf(resource("a")))
        LedgerWriter(f.backend).recordDiscovered(listOf(resource("a")))
        val result = f.cycle().topUp(never)

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a"), f.library.created)
        assertEquals(0, f.library.walks, "a completion's top-up costs no enumeration")
        assertEquals(0, f.publishes, "and publishes nothing: only discovery changes the manifest")
    }

    @Test
    fun `a walk that found nothing new reports no added rows`() = runTest {
        val f = Fixture(listOf(resource("a")))
        val cycle = f.cycle()
        cycle.walkAndPublish(never)
        assertEquals(WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false), cycle.walkAndPublish(never))
    }

    @Test
    fun `a stop during the walk records nothing and publishes nothing`() = runTest {
        val f = Fixture(listOf(resource("a")))
        val outcome = f.cycle().walkAndPublish { true }

        assertEquals(WalkOutcome.Abandoned, outcome)
        assertEquals(null, f.backend.get("a"), "nothing the abandoned walk saw is recorded")
        assertEquals(0, f.publishes, "and no manifest is published from it")
    }

    @Test
    fun `a stop ends the top-up between two creations and reports work left`() = runTest {
        val f = Fixture(listOf(resource("a"), resource("b"), resource("c")))
        LedgerWriter(f.backend).recordDiscovered(listOf(resource("a"), resource("b"), resource("c")))
        var creations = 0
        val result = f.cycle().topUp { creations++ >= 1 }

        assertEquals(CycleResult.PROCESSING, result, "rows not reached still need a job")
        assertEquals(listOf("a"), f.library.created, "the creation in flight completed, and no further one started")
        assertEquals(LedgerState.DISCOVERED, f.backend.get("b")?.state)
    }

    @Test
    fun `both units decline at the entry gate exactly as a whole cycle does`() = runTest {
        for ((gate, expected) in listOf(
            CycleGate.NotJoined to CycleResult.SKIPPED,
            CycleGate.Skip("unreadable") to CycleResult.COMPLETED,
            CycleGate.Withheld(UploadConfig(host = "https://edge.example", eventId = "E")) to CycleResult.SKIPPED,
        )) {
            val f = Fixture(listOf(resource("a")))
            val cycle = f.cycle(gate)
            assertEquals(expected, cycle.topUp(never), "top-up at $gate")
            assertEquals(WalkOutcome.Walked(expected, addedRows = false), cycle.walkAndPublish(never), "walk at $gate")
            assertTrue(f.library.created.isEmpty())
            assertEquals(0, f.library.walks, "a declined unit reads no library")
        }
    }

    @Test
    fun `a membership that contributes nothing publishes its empty manifest from the walk and creates nothing`() =
        runTest {
            val f = Fixture(listOf(resource("a")))
            val cycle = f.cycle(policy = app.snapsync.model.noContribution())
            assertEquals(CycleResult.SKIPPED, cycle.topUp(never))
            assertEquals(0, f.publishes, "the top-up publishes nothing")
            assertEquals(WalkOutcome.Walked(CycleResult.SKIPPED, addedRows = false), cycle.walkAndPublish(never))
            assertEquals(1, f.publishes, "the walk publishes the empty manifest")
        }

    @Test
    fun `a refused creation truncates the top-up`() = runTest {
        val f = Fixture(listOf(resource("a"), resource("b")), limit = 1)
        LedgerWriter(f.backend).recordDiscovered(listOf(resource("a"), resource("b")))
        assertEquals(CycleResult.PROCESSING, f.cycle().topUp(never))
    }
}

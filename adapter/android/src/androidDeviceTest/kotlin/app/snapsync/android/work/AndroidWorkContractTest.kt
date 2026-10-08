package app.snapsync.android.work

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.snapsync.android.gallery.MediaStoreSeeder
import app.snapsync.android.network.MeteredWifi
import app.snapsync.android.network.awaitUnrestrictedNetwork
import app.snapsync.android.storage.Airplane
import app.snapsync.android.storage.context
import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeOs
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.model.AssetId
import app.snapsync.model.ScheduleResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The WorkManager adapters against their port contracts on the emulator, and the Android facts no shared contract
 * states: a library change wakes the app; a wake re-armed from inside its own run is not cancelled by it; a transfer a
 * dead process left is reported failed. The upload clauses exchange bytes with `scripts/transfer-fixture.py`, whose
 * address the Gradle test run (`androidPlatformTest`) passes as the `fixture` instrumentation argument; a run without
 * it fails.
 */
class AndroidWorkContractTest {

    private val work get() = WorkManager.getInstance(context)

    private suspend fun pending(name: String): Int = withContext(Dispatchers.IO) {
        work.getWorkInfosForUniqueWork(name).get().count { !it.state.isFinished }
    }

    private val wake = object : Binding<WakeState, ScheduledWakes> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(WakeState.EMPTY, WakeState.DELIVERING)

        override fun create(state: WakeState, clauseId: String, log: CallLog): Entered<ScheduledWakes> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "Android has both wakes, and WorkManager refuses no request",
                )
            }
            val adapter = AndroidWake(context)
            WakeId.entries.forEach(adapter::cancel)
            val name = AndroidWake.nameOf(WakeId.Heartbeat)
            val seeded = mutableSetOf<AssetId>()
            // A new photo in the default gallery changes the library; the job scheduler's timeout is the system's end.
            // WorkManager runs a due worker in this process rather than through the job scheduler, so the system's end of
            // a running wake that reaches it is a lost constraint: the heartbeat needs a network, and airplane mode takes
            // it away.
            val os = WakeOs(
                changeLibrary = { seeded += MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-03T12:00:00Z", count = 1) },
                endRunningWake = { Airplane.enter() },
            )
            return Entered.Ready(
                ScheduledWakes(adapter, os.takeIf { state == WakeState.DELIVERING }) { pending(name) },
            ) {
                WakeId.entries.forEach(adapter::cancel)
                MediaStoreSeeder.delete(seeded)
                Airplane.leave()
            }
        }
    }

    private val backgroundTime = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(BackgroundTimeState.TIME_REMAINS)
        override fun create(state: BackgroundTimeState, clauseId: String, log: CallLog): Entered<BackgroundTime> =
            if (state in reaches) {
                Entered.Ready(AndroidBackgroundTime(context))
            } else {
                // An expedited work's stop comes when WorkManager's quota or the system's constraints end it, which a
                // device test cannot bring about on a work it runs in-process (`docs/testing.md`).
                Entered.Unreachable("no device test brings an expedited work's stop about")
            }
    }

    private val upload = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            UploadState.IDLE,
            UploadState.AT_CAP,
            UploadState.RESTRICTED_NETWORK,
            UploadState.TAKES_RESOURCES_AND_FILES,
            UploadState.REPORTS_AS_IT_HAPPENS,
        )

        override fun create(state: UploadState, clauseId: String, log: CallLog): Entered<UploadUnderTest> {
            if (state in UploadContract.PRESENTED) {
                return Entered.Unreachable("Android reports every transfer's end as it happens; nothing is presented")
            }
            if (state !in reaches) return Entered.Unreachable("Android takes the library's own items, and files too")
            val base = fixture()
            // The fixture is the host's, reached over the emulator's Wi-Fi: metering the Wi-Fi meters the transfer.
            if (state == UploadState.RESTRICTED_NETWORK) MeteredWifi.enter()
            val seeded = mutableSetOf<AssetId>()
            val ended = Collections.synchronizedList(mutableListOf<UploadJob>())
            val bare = AndroidUpload(
                context,
                AndroidBackgroundTime(context),
                maxLive = CAP,
                journalName = "contract.$clauseId",
                unrestricted = awaitUnrestrictedNetwork(context),
            )
            val adapter = bare.recorded(log)
            adapter.listen(
                UploadHandlers(onFinished = {
                    ended += it
                }, onBackgroundEvents = { it.complete() }, onEventsDrained = {}),
            )
            val usable: suspend (String) -> UploadSource = { _ ->
                val id = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-01T12:00:00Z", count = 1).single()
                seeded += id
                UploadSource.Resource(
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        id.value.toLong(),
                    ),
                )
            }
            if (state == UploadState.AT_CAP) {
                runEntry {
                    repeat(CAP) { n ->
                        val key = UploadContract.key(clauseId, n = n + 1)
                        val url = base + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val target = UploadTarget(url, mapOf("Content-Type" to "image/jpeg"), TransferNetwork.ANY)
                        val created = adapter.create(usable(key), target, key)
                        check(
                            created == UploadCreateOutcome.CREATED,
                        ) { "filling the cap: transfer ${n + 1} was $created" }
                    }
                }
            }
            return Entered.Ready(
                UploadUnderTest(
                    upload = adapter,
                    base = base,
                    usable = usable,
                    unusable = { UploadSource.File("/nonexistent/$it") },
                    ended = { synchronized(ended) { ended.toList() } },
                    objects = landedAt(base),
                    liftRestriction = { MeteredWifi.lift() },
                    fileSource = { key ->
                        UploadSource.File(
                            File(context.cacheDir, key).apply { writeBytes(PhotoLibrary.jpeg) }.absolutePath,
                        )
                    },
                ),
            ) {
                // The cleanup is the binding's, not the clause's: it goes round the proxy, so it claims nothing.
                runEntry { bare.jobs(UploadJobSet.IN_FLIGHT).forEach { bare.cancel(it) } }
                MediaStoreSeeder.delete(seeded)
                if (state == UploadState.RESTRICTED_NETWORK) MeteredWifi.lift()
            }
        }
    }

    @Test
    fun `WorkManager satisfies the Wake contract`() = verify(WakeContract, wake)

    @Test
    fun `expedited work satisfies the BackgroundTime contract`() = verify(BackgroundTimeContract, backgroundTime)

    @Test
    fun `in-process transfers satisfy the Upload contract`() = verify(UploadContract, upload)

    @Test
    fun `a library change wakes the app`() {
        val woken = CompletableDeferred<WakeId>()
        val adapter = AndroidWake(context)
        adapter.listen(
            WakeHandlers { id, completion ->
                woken.complete(id)
                completion.complete()
            },
        )
        val watched = runBlocking {
            adapter.schedule(
                WakeId.LibraryChanged,
                WakeTrigger.LibraryChange(maxDelay = 1.seconds),
            )
        }
        assertEquals(ScheduleResult.Scheduled, watched)
        val seeded = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-02T12:00:00Z", count = 1)
        try {
            val id = runBlocking { withTimeoutOrNull(WAIT_MILLIS) { woken.await() } }
            assertEquals(WakeId.LibraryChanged, assertNotNull(id, "a new photo woke the app within a minute"))
        } finally {
            MediaStoreSeeder.delete(seeded)
            adapter.cancel(WakeId.LibraryChanged)
        }
    }

    /**
     * Capability `mobile-data`: a wake that waits for an unrestricted network (a busy heartbeat for a member who keeps
     * photos off mobile data) does not run on a metered one, and runs once the network is unmetered — WorkManager's own
     * unmetered constraint, which is what resumes a held upload on Android.
     */
    @Test
    fun `an unrestricted wake waits out a metered network and runs on an unmetered one`() {
        val ran = CompletableDeferred<Unit>()
        val adapter = AndroidWake(context)
        adapter.listen(
            WakeHandlers { _, completion ->
                ran.complete(Unit)
                completion.complete()
            },
        )
        MeteredWifi.enter()
        try {
            runBlocking {
                adapter.schedule(
                    WakeId.Heartbeat,
                    WakeTrigger.After(Duration.ZERO, network = WakeNetwork.UNRESTRICTED),
                )
            }
            val early = runBlocking { withTimeoutOrNull(HELD_MILLIS) { ran.await() } }
            assertEquals(null, early, "the wake ran on a metered network")
            MeteredWifi.lift()
            val later = runBlocking { withTimeoutOrNull(WAIT_MILLIS) { ran.await() } }
            assertEquals(Unit, later, "the wake did not run once the network was unmetered")
        } finally {
            MeteredWifi.lift()
            adapter.cancel(WakeId.Heartbeat)
        }
    }

    @Test
    fun `a wake re-armed from inside its own run is not cancelled by it`() {
        val done = CompletableDeferred<Boolean>()
        val adapter = AndroidWake(context)
        val later = WakeTrigger.After(1.hours, network = WakeNetwork.NONE)
        adapter.listen(
            WakeHandlers { id, completion ->
                var expired = false
                completion.onExpired { expired = true }
                runBlocking { adapter.schedule(id, later) }
                Thread.sleep(REPLACE_WINDOW_MILLIS) // long enough for a REPLACE to have cancelled this very run
                done.complete(!expired)
                completion.complete()
            },
        )
        runBlocking { adapter.schedule(WakeId.Heartbeat, WakeTrigger.After(Duration.ZERO, network = WakeNetwork.NONE)) }
        try {
            val survived = runBlocking { withTimeoutOrNull(WAIT_MILLIS) { done.await() } }
            assertEquals(true, survived, "the running wake finished rather than being stopped by its own re-arm")
            val name = AndroidWake.nameOf(WakeId.Heartbeat)
            runBlocking {
                delay(REPLACE_WINDOW_MILLIS)
                assertEquals(1, pending(name), "and its re-arm waits for its own trigger")
            }
        } finally {
            adapter.cancel(WakeId.Heartbeat)
        }
    }

    @Test
    fun `a transfer a dead process left is reported failed at the next start`() {
        val base = fixture()
        val journal = "contract.relaunch"
        val first = AndroidUpload(
            context,
            AndroidBackgroundTime(context),
            journalName = journal,
            unrestricted = awaitUnrestrictedNetwork(context),
        )
        first.listen(UploadHandlers(onFinished = {}, onBackgroundEvents = { it.complete() }, onEventsDrained = {}))
        val seeded = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-03T12:00:00Z", count = 1)
        val key = UploadContract.key("RELAUNCH")
        try {
            runBlocking {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    seeded.single().value.toLong(),
                )
                val url = base + UploadContract.path("RELAUNCH", FixtureAnswer.Hold)
                assertEquals(
                    UploadCreateOutcome.CREATED,
                    first.create(UploadSource.Resource(uri), UploadTarget(url, emptyMap(), TransferNetwork.ANY), key),
                )
            }
            // The next process: a fresh adapter over the same journal, knowing nothing live.
            val reported = mutableListOf<UploadJob>()
            AndroidUpload(
                context,
                AndroidBackgroundTime(context),
                journalName = journal,
                unrestricted = awaitUnrestrictedNetwork(context),
            )
                .listen(
                    UploadHandlers(
                        onFinished = { reported += it },
                        onBackgroundEvents = { it.complete() },
                        onEventsDrained = {},
                    ),
                )
            val job = assertNotNull(
                reported.singleOrNull { it.tag == key },
                "the orphaned transfer is reported: $reported",
            )
            assertTrue(job.state == UploadJobState.FAILED, "as a failure, so its row is retried")
        } finally {
            runBlocking { first.jobs(UploadJobSet.IN_FLIGHT).forEach { first.cancel(it) } }
            MediaStoreSeeder.delete(seeded)
            context.deleteSharedPreferences(journal)
        }
    }

    private fun fixture(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("fixture")) {
        "no transfer fixture: run ./gradlew androidPlatformTest, which serves one and passes its address"
    }.trimEnd('/')

    private fun landedAt(base: String) = FixtureObjects { path ->
        withContext(Dispatchers.IO) {
            val connection = URL("$base/_landed$path").openConnection() as HttpURLConnection
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = connection.inputStream.use { it.readBytes().decodeToString() }
                Landed(Regex("\"contentType\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1))
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        /** Production's cap is 4; a smaller one enters the cap with fewer photos through the same path. */
        const val CAP = 2

        /** How long a wake the operating system owes is waited for: a library change is due within its 1 s delay. */
        const val WAIT_MILLIS = 60_000L

        /** How long an unrestricted wake must stay unrun on a metered network — beyond a constraint-free wake's start. */
        const val HELD_MILLIS = 10_000L

        /** Long enough for a REPLACE to have cancelled a running wake, and for its re-arm to be recorded. */
        const val REPLACE_WINDOW_MILLIS = 1_000L
    }
}

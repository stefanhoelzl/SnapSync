package app.snapsync.android.work

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.snapsync.android.gallery.MediaStoreResource
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
import app.snapsync.contracts.Ended
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.SessionUnderTest
import app.snapsync.contracts.TransferSessionContract
import app.snapsync.contracts.TransferSessionState
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeOs
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.uploadClauseKey
import app.snapsync.contracts.verify
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            val bare = AndroidWake(context)
            val adapter = bare.recorded(log)
            WakeId.entries.forEach(bare::cancel)
            val name = AndroidWake.nameOf(WakeId.Heartbeat)
            val seeded = mutableSetOf<PlatformAssetId>()
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
                // The cleanup is the binding's, not the clause's: it goes round the proxy, so it claims nothing.
                WakeId.entries.forEach(bare::cancel)
                MediaStoreSeeder.delete(seeded)
                Airplane.leave()
            }
        }
    }

    private val backgroundTime = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(BackgroundTimeState.TIME_REMAINS, BackgroundTimeState.TIME_RUNS_OUT)
        override fun create(state: BackgroundTimeState, clauseId: String, log: CallLog): Entered<BackgroundTime> {
            val adapter = AndroidBackgroundTime(context).recorded(log)
            if (state == BackgroundTimeState.TIME_REMAINS) return Entered.Ready(adapter)
            // The end of a running hold. WorkManager runs a hold's worker in this process rather than through the job
            // scheduler, so the scheduler's own timeout never reaches it (measured: `cmd jobscheduler timeout` left the
            // clause waiting out its bound). Every stop — the quota's, a constraint's, the system's — reaches the worker
            // through WorkManager's one stop path, which this plays as soon as a hold's worker is running.
            val ended = AtomicBoolean(false)
            val watcher = thread(name = "contract.stop") {
                while (!ended.get()) {
                    running().forEach { work.cancelWorkById(it).result.get() }
                    Thread.sleep(POLL_MILLIS)
                }
            }
            return Entered.Ready(adapter) {
                ended.set(true)
                watcher.join()
            }
        }
    }

    private fun running(): List<UUID> {
        val holds = work.getWorkInfosByTag(HoldWorker::class.java.name).get()
        return holds.filter { it.state == WorkInfo.State.RUNNING }.map { it.id }
    }

    private val upload = object : Binding<TransferSessionState, SessionUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            TransferSessionState.IDLE,
            TransferSessionState.AT_CAP,
            TransferSessionState.RESTRICTED_NETWORK,
            TransferSessionState.TAKES_RESOURCES_AND_FILES,
            TransferSessionState.REPORTS_AS_IT_HAPPENS,
            TransferSessionState.READS_FILES_FROM_DISK,
        )

        override fun create(state: TransferSessionState, clauseId: String, log: CallLog): Entered<SessionUnderTest> {
            if (state !in reaches) return Entered.Unreachable("Android takes the library's own items, and files too")
            val base = fixture()
            // The fixture is the host's, reached over the emulator's Wi-Fi: metering the Wi-Fi meters the transfer.
            if (state == TransferSessionState.RESTRICTED_NETWORK) MeteredWifi.enter()
            val seeded = mutableSetOf<PlatformAssetId>()
            val ended = Collections.synchronizedList(mutableListOf<Ended>())
            val bare = AndroidUpload(
                context,
                AndroidBackgroundTime(context),
                maxLive = CAP,
                journalName = "contract.$clauseId",
                unrestricted = awaitUnrestrictedNetwork(context),
            )
            val adapter = bare.recorded(log)
            adapter.listen(
                TransferHandlers(
                    onFinished = { tag, error -> ended += Ended(tag, error) },
                    onBackgroundEvents = { it.complete() },
                    onEventsDrained = {},
                ),
            )
            val usable: suspend (String) -> TransferSource = { _ ->
                val id = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-01T12:00:00Z", count = 1).single()
                seeded += id
                TransferSource.Resource(
                    MediaStoreResource(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            id.value.toLong(),
                        ),
                    ),
                )
            }
            if (state == TransferSessionState.AT_CAP) {
                runEntry {
                    repeat(CAP) { n ->
                        val key = uploadClauseKey(clauseId, n = n + 1)
                        val url = base + TransferSessionContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val target = UploadTarget(url, mapOf("Content-Type" to "image/jpeg"), TransferNetwork.ANY)
                        val created = adapter.create(usable(key), target, key)
                        check(
                            created == CreateOutcome.CREATED,
                        ) { "filling the cap: transfer ${n + 1} was $created" }
                    }
                }
            }
            return Entered.Ready(
                SessionUnderTest(
                    session = adapter,
                    base = base,
                    usable = usable,
                    unusable = { TransferSource.File("/nonexistent/$it") },
                    ended = { synchronized(ended) { ended.toList() } },
                    objects = landedAt(base),
                    liftRestriction = { MeteredWifi.lift() },
                    fileSource = { key ->
                        TransferSource.File(
                            File(context.cacheDir, key).apply { writeBytes(PhotoLibrary.jpeg) }.absolutePath,
                        )
                    },
                ),
            ) {
                // The cleanup is the binding's, not the clause's: it goes round the proxy, so it claims nothing.
                runEntry { bare.live().forEach { bare.cancel(it) } }
                MediaStoreSeeder.delete(seeded)
                if (state == TransferSessionState.RESTRICTED_NETWORK) MeteredWifi.lift()
            }
        }
    }

    @Test
    fun `WorkManager satisfies the Wake contract`() = verify(WakeContract, wake)

    @Test
    fun `expedited work satisfies the BackgroundTime contract`() = verify(BackgroundTimeContract, backgroundTime)

    @Test
    fun `in-process transfers satisfy the TransferSession contract`() = verify(TransferSessionContract, upload)

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
        first.listen(
            TransferHandlers(onFinished = { _, _ -> }, onBackgroundEvents = { it.complete() }, onEventsDrained = {}),
        )
        val seeded = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-03T12:00:00Z", count = 1)
        val key = uploadClauseKey("RELAUNCH")
        try {
            runBlocking {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    seeded.single().value.toLong(),
                )
                val url = base + TransferSessionContract.path("RELAUNCH", FixtureAnswer.Hold)
                assertEquals(
                    CreateOutcome.CREATED,
                    first.create(
                        TransferSource.Resource(MediaStoreResource(uri)),
                        UploadTarget(url, emptyMap(), TransferNetwork.ANY),
                        key,
                    ),
                )
            }
            // The next process: a fresh adapter over the same journal, knowing nothing live.
            val reported = mutableListOf<Ended>()
            AndroidUpload(
                context,
                AndroidBackgroundTime(context),
                journalName = journal,
                unrestricted = awaitUnrestrictedNetwork(context),
            )
                .listen(
                    TransferHandlers(
                        onFinished = { tag, error -> reported += Ended(tag, error) },
                        onBackgroundEvents = { it.complete() },
                        onEventsDrained = {},
                    ),
                )
            val ended = assertNotNull(
                reported.singleOrNull { it.tag == key },
                "the orphaned transfer is reported: $reported",
            )
            assertNotNull(ended.failure, "as a failure, so its row is retried")
        } finally {
            runBlocking { first.live().forEach { first.cancel(it) } }
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

        /** How often the end of a hold looks for a running hold to stop. */
        const val POLL_MILLIS = 250L
    }
}

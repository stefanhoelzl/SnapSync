package app.snapsync.android.work

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.snapsync.android.gallery.MediaStoreSeeder
import app.snapsync.android.storage.context
import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.model.AssetId
import app.snapsync.model.ScheduleResult
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.WakeHandlers
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        override val reaches = setOf(WakeState.EMPTY)

        override fun create(state: WakeState, clauseId: String): Entered<ScheduledWakes> {
            val adapter = AndroidWake(context)
            WakeId.entries.forEach(adapter::cancel)
            val name = AndroidWake.nameOf(WakeId.Heartbeat)
            return Entered.Ready(ScheduledWakes(adapter) { pending(name) }) {
                WakeId.entries.forEach(adapter::cancel)
            }
        }
    }

    private val backgroundTime = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(BackgroundTimeState.TIME_REMAINS)
        override fun create(state: BackgroundTimeState, clauseId: String): Entered<BackgroundTime> =
            Entered.Ready(AndroidBackgroundTime(context))
    }

    private val upload = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(UploadState.IDLE, UploadState.AT_CAP)

        override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
            if (state in UploadContract.PRESENTED) {
                return Entered.Unreachable("Android reports every transfer's end as it happens; nothing is presented")
            }
            val base = fixture()
            val seeded = mutableSetOf<AssetId>()
            val ended = Collections.synchronizedList(mutableListOf<UploadJob>())
            val adapter = AndroidUpload(context, AndroidBackgroundTime(context), maxLive = CAP, journalName = "contract.$clauseId")
            adapter.listen(UploadHandlers(onFinished = { ended += it }, onBackgroundEvents = { it.complete() }, onEventsDrained = {}))
            val usable: suspend (String) -> UploadSource = { _ ->
                val id = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-01T12:00:00Z", count = 1).single()
                seeded += id
                UploadSource.Resource(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id.value.toLong()))
            }
            if (state == UploadState.AT_CAP) {
                runEntry {
                    repeat(CAP) { n ->
                        val key = UploadContract.key(clauseId, n = n + 1)
                        val url = base + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val created = adapter.create(usable(key), UploadTarget(url, mapOf("Content-Type" to "image/jpeg")), key)
                        check(created == UploadCreateOutcome.CREATED) { "filling the cap: transfer ${n + 1} was $created" }
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
                ),
            ) {
                runEntry { adapter.jobs(UploadJobSet.IN_FLIGHT).forEach { adapter.cancel(it) } }
                MediaStoreSeeder.delete(seeded)
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
        adapter.listen(WakeHandlers { id, completion -> woken.complete(id); completion.complete() })
        val watched = adapter.schedule(
            WakeId.LibraryChanged,
            WakeTrigger.LibraryChange(maxDelay = 1.seconds),
        )
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

    @Test
    fun `a wake re-armed from inside its own run is not cancelled by it`() {
        val done = CompletableDeferred<Boolean>()
        val adapter = AndroidWake(context)
        val later = WakeTrigger.After(1.hours, requiresNetwork = false)
        adapter.listen(
            WakeHandlers { id, completion ->
                var expired = false
                completion.onExpired { expired = true }
                adapter.schedule(id, later)
                Thread.sleep(REPLACE_WINDOW_MILLIS) // long enough for a REPLACE to have cancelled this very run
                done.complete(!expired)
                completion.complete()
            },
        )
        adapter.schedule(WakeId.Heartbeat, WakeTrigger.After(Duration.ZERO, requiresNetwork = false))
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
        val first = AndroidUpload(context, AndroidBackgroundTime(context), journalName = journal)
        first.listen(UploadHandlers(onFinished = {}, onBackgroundEvents = { it.complete() }, onEventsDrained = {}))
        val seeded = MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-03T12:00:00Z", count = 1)
        val key = UploadContract.key("RELAUNCH")
        try {
            runBlocking {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), seeded.single().value.toLong())
                val url = base + UploadContract.path("RELAUNCH", FixtureAnswer.Hold)
                assertEquals(UploadCreateOutcome.CREATED, first.create(UploadSource.Resource(uri), UploadTarget(url, emptyMap()), key))
            }
            // The next process: a fresh adapter over the same journal, knowing nothing live.
            val reported = mutableListOf<UploadJob>()
            AndroidUpload(context, AndroidBackgroundTime(context), journalName = journal)
                .listen(UploadHandlers(onFinished = { reported += it }, onBackgroundEvents = { it.complete() }, onEventsDrained = {}))
            val job = assertNotNull(reported.singleOrNull { it.tag == key }, "the orphaned transfer is reported: $reported")
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

        /** Long enough for a REPLACE to have cancelled a running wake, and for its re-arm to be recorded. */
        const val REPLACE_WINDOW_MILLIS = 1_000L
    }
}

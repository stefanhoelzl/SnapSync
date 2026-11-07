@file:OptIn(CompositionOnly::class)

package app.snapsync.android.download

import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.network.DataSaver
import app.snapsync.android.network.MeteredWifi
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClauseDownloadHandlers
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadEvent
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.control.Verifies
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DownloadManager` against the [DownloadContract] on the emulator, and the Android fact no shared contract states: a
 * transfer that finished while no completion broadcast reached the app is delivered by the next start's pass. The
 * clauses fetch from `scripts/transfer-fixture.py` (served on the host by the Gradle test run, `10.0.2.2` here); the
 * download provider honours this test APK's cleartext permission for the loopback.
 *
 * The test APK declares no manifest receiver, so the binding registers [DownloadCompleteReceiver] at run time — the
 * same class the app's manifest names — which reaches the adapter each clause listened on last.
 */
class AndroidDownloadContractTest {

    private val receiver = DownloadCompleteReceiver()

    @BeforeTest
    fun registerReceiver() {
        awaitBroadcastsIdle()
        removeAllDownloads()
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED,
        )
    }

    @AfterTest
    fun unregisterReceiver() {
        runCatching { context.unregisterReceiver(receiver) }
        removeAllDownloads()
    }

    private val download = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidDownload::class
        override val reaches =
            setOf(
                DownloadState.READY,
                DownloadState.RESTRICTED_NETWORK,
                DownloadState.WAKES_TO_DELIVER,
                DownloadState.REFUSES_UNFETCHABLE,
            )

        override fun create(state: DownloadState, clauseId: String, log: CallLog): Entered<DownloadUnderTest> {
            if (state !in reaches) return Entered.Unreachable("DownloadManager never relaunches the app")
            removeAllDownloads()
            // The fixture is the host's, reached over the emulator's Wi-Fi: metering the Wi-Fi meters the transfer.
            if (state == DownloadState.RESTRICTED_NETWORK) MeteredWifi.enter()
            return Entered.Ready(
                DownloadUnderTest(
                    open = { AndroidDownload(context).recorded(log) },
                    base = fixture(),
                    readTemp = { path -> runCatching { File(path).readBytes() }.getOrNull() },
                    liftRestriction = { MeteredWifi.lift() },
                    // The completion broadcast is the wake: it is held (goAsync) until the delivery releases it.
                    wakeEnded = { broadcastsIdleWithin(BROADCAST_IDLE_MILLIS) },
                ),
            ) {
                removeAllDownloads()
                if (state == DownloadState.RESTRICTED_NETWORK) MeteredWifi.lift()
            }
        }
    }

    /**
     * RESTRICTED_NETWORK the way Android's Data Saver makes it of an unmetered Wi-Fi:
     * DownloadManager would run the transfer, so only the adapter's parking holds it.
     */
    private val downloadUnderDataSaver = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidDownload::class
        override val reaches = setOf(DownloadState.RESTRICTED_NETWORK)

        override fun create(state: DownloadState, clauseId: String, log: CallLog): Entered<DownloadUnderTest> {
            if (state !in reaches) return Entered.Unreachable("this binding enters RESTRICTED_NETWORK by Data Saver")
            removeAllDownloads()
            DataSaver.enter()
            return Entered.Ready(
                DownloadUnderTest(
                    open = { AndroidDownload(context).recorded(log) },
                    base = fixture(),
                    readTemp = { path -> runCatching { File(path).readBytes() }.getOrNull() },
                    liftRestriction = { DataSaver.lift() },
                    wakeEnded = { broadcastsIdleWithin(BROADCAST_IDLE_MILLIS) },
                ),
            ) {
                DataSaver.lift()
                removeAllDownloads()
            }
        }
    }

    @Test
    fun `DownloadManager satisfies the Download contract`() = verify(DownloadContract, download)

    @Test
    fun `DownloadManager satisfies the Download contract under Data Saver`() =
        verify(DownloadContract, downloadUnderDataSaver)

    // The receiving half of the scenario: a download waits out Data Saver across a process death and then arrives.
    // The sharing half is the upload's (`AndroidWorkContractTest`, under Data Saver).
    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "Data Saver holds photos on an ordinary Wi-Fi",
    )
    fun `a download parked under Data Saver survives the process and arrives once Data Saver is off`(): Unit = runBlocking {
        try {
            DataSaver.enter()
            val first = AndroidDownload(context)
            first.inbound(ClauseDownloadHandlers { null }.handlers)
            val path = DownloadContract.path("PARKED", FixtureAnswer.Respond(HTTP_OK, length = MISSED_LENGTH))
            val url = fixture() + path
            val started = first.start(url, emptyMap(), "d-parked", TransferNetwork.UNRESTRICTED_ONLY)
            assertEquals(StartResult.Started, started)
            delay(HELD_MILLIS)
            assertEquals(0, rowCount(), "parked: DownloadManager holds no row it would run on this Wi-Fi")

            // A new process: a new adapter, listened on, while Data Saver still holds the app — still parked.
            val relaunched = ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
            AndroidDownload(context).inbound(relaunched.handlers)
            assertEquals(0, rowCount(), "still parked after the relaunch")

            DataSaver.lift()
            withTimeout(TRANSFER_WAIT_MILLIS) {
                while (relaunched.events.none { it is DownloadEvent.Finished }) delay(POLL_MILLIS)
            }
            val delivered = relaunched.events.filterIsInstance<DownloadEvent.Finished>().single()
            assertEquals("d-parked", delivered.tag)
            assertEquals(MISSED_LENGTH, delivered.body?.size, "the body arrives whole")
        } finally {
            DataSaver.lift()
        }
    }

    @Test
    fun `cancelling every download drops a parked one so it never arrives`(): Unit = runBlocking {
        try {
            DataSaver.enter()
            val handlers = ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
            val adapter = AndroidDownload(context)
            adapter.inbound(handlers.handlers)
            val path = DownloadContract.path("PARKED_CANCELLED", FixtureAnswer.Respond(HTTP_OK, length = MISSED_LENGTH))
            assertEquals(
                StartResult.Started,
                adapter.start(fixture() + path, emptyMap(), "d-dropped", TransferNetwork.UNRESTRICTED_ONLY),
            )
            adapter.cancelAll()
            val reported = handlers.events.last()
            assertEquals(DownloadEvent.Completed("d-dropped", "cancelled"), reported, "reported cancelled")
            DataSaver.lift()
            delay(HELD_MILLIS)
            assertEquals(0, rowCount(), "nothing was enqueued once Data Saver was off")
            assertTrue(handlers.events.none { it is DownloadEvent.Finished }, "and nothing arrived")
        } finally {
            DataSaver.lift()
        }
    }

    private fun rowCount(): Int = downloadManager.query(DownloadManager.Query())?.use { it.count } ?: 0

    @Test
    fun `a transfer that finished while no broadcast arrived is delivered by the next start`(): Unit = runBlocking {
        // A force-stopped app receives no broadcasts; the rows stay with DownloadManager until someone asks.
        context.unregisterReceiver(receiver)
        val first = AndroidDownload(context)
        first.inbound(ClauseDownloadHandlers { null }.handlers)
        val path = DownloadContract.path("MISSED_BROADCAST", FixtureAnswer.Respond(HTTP_OK, length = MISSED_LENGTH))
        assertEquals(StartResult.Started, first.start(fixture() + path, emptyMap(), "d-missed", TransferNetwork.ANY))
        withTimeout(TRANSFER_WAIT_MILLIS) {
            while (!finished()) delay(POLL_MILLIS)
        }

        val relaunched = ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
        AndroidDownload(context).inbound(relaunched.handlers)

        val delivered = relaunched.events.filterIsInstance<DownloadEvent.Finished>().single()
        assertEquals("d-missed", delivered.tag)
        assertEquals(MISSED_LENGTH, delivered.body?.size, "the body is handed over, inline")
        assertEquals(DownloadEvent.Completed("d-missed", null), relaunched.events.last())
        assertTrue(downloadManager.query(DownloadManager.Query())?.use { it.count } == 0, "a delivered row is removed")
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED,
        )
    }

    private fun finished(): Boolean = downloadManager.query(DownloadManager.Query()).use { cursor ->
        cursor.moveToFirst() &&
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
    }

    private fun fixture(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("fixture")) {
        "no transfer fixture: run ./gradlew androidPlatformTest, which serves one and passes its address"
    }.trimEnd('/')

    private companion object {
        const val TRANSFER_WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 200L
        const val HTTP_OK = 200
        const val MISSED_LENGTH = 256

        /** How long a parked download is watched not to run: DownloadManager starts an allowed one within ~6 s. */
        const val HELD_MILLIS = 8_000L

        /** Well under the receiver's own 50 s budget: a wake released at once is long over by then. */
        const val BROADCAST_IDLE_MILLIS = 10_000L
    }
}

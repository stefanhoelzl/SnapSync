package app.snapsync.android.download

import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.network.MeteredWifi
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ClauseDownloadHandlers
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadEvent
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.model.StartResult
import app.snapsync.model.TransferNetwork
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
        override val reaches = setOf(DownloadState.READY, DownloadState.RESTRICTED_NETWORK)

        override fun create(state: DownloadState, clauseId: String): Entered<DownloadUnderTest> {
            removeAllDownloads()
            // The fixture is the host's, reached over the emulator's Wi-Fi: metering the Wi-Fi meters the transfer.
            if (state == DownloadState.RESTRICTED_NETWORK) MeteredWifi.enter()
            return Entered.Ready(
                DownloadUnderTest(
                    open = { AndroidDownload(context) },
                    base = fixture(),
                    readTemp = { path -> runCatching { File(path).readBytes() }.getOrNull() },
                    liftRestriction = { MeteredWifi.lift() },
                ),
            ) {
                removeAllDownloads()
                if (state == DownloadState.RESTRICTED_NETWORK) MeteredWifi.lift()
            }
        }
    }

    @Test
    fun `DownloadManager satisfies the Download contract`() = verify(DownloadContract, download)

    @Test
    fun `a transfer that finished while no broadcast arrived is delivered by the next start`(): Unit = runBlocking {
        // A force-stopped app receives no broadcasts; the rows stay with DownloadManager until someone asks.
        context.unregisterReceiver(receiver)
        val first = AndroidDownload(context)
        first.listen(ClauseDownloadHandlers { null }.handlers)
        val path = DownloadContract.path("MISSED_BROADCAST", FixtureAnswer.Respond(HTTP_OK, length = MISSED_LENGTH))
        assertEquals(StartResult.Started, first.start(fixture() + path, "d-missed", TransferNetwork.ANY))
        withTimeout(TRANSFER_WAIT_MILLIS) {
            while (!finished()) delay(POLL_MILLIS)
        }

        val relaunched = ClauseDownloadHandlers { runCatching { File(it).readBytes() }.getOrNull() }
        AndroidDownload(context).listen(relaunched.handlers)

        val delivered = relaunched.events.filterIsInstance<DownloadEvent.Finished>().single()
        assertEquals("d-missed", delivered.tag)
        assertEquals(MISSED_LENGTH, delivered.body?.size, "the body is handed over, inline")
        assertEquals(DownloadEvent.Completed("d-missed", null), relaunched.events.last())
        assertTrue(downloadManager.query(DownloadManager.Query())?.use { it.count } == 0, "a delivered row is removed")
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
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
    }
}

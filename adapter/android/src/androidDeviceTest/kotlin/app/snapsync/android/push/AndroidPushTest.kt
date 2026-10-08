package app.snapsync.android.push

import app.snapsync.android.process.AndroidProcessInfo
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ProcessInfoContract
import app.snapsync.contracts.ProcessInfoState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.PUSH_KIND_FCM
import app.snapsync.model.PlatformError
import app.snapsync.model.PushToken
import app.snapsync.model.pushEventId
import app.snapsync.ports.Completion
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.PushHandlers
import com.google.firebase.messaging.RemoteMessage
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The Android push service's entry points and the process read, on the emulator. What FCM itself does — issue a
 * token, deliver a high-priority message to a dozing phone — needs a Firebase project and Google Play services, and is
 * measured in the closed test (`docs/testing.md`); here the adapter is driven at its own entries, as FCM calls them.
 */
class AndroidPushTest {

    private val tokens = Collections.synchronizedList(mutableListOf<PushToken>())
    private val failures = Collections.synchronizedList(mutableListOf<PlatformError?>())
    private val messages = Collections.synchronizedList(mutableListOf<Pair<String?, Completion>>())

    private fun adapter(
        config: FirebaseConfig = FirebaseConfig(
            "",
            "",
            "",
            "",
        ),
    ) = AndroidPushNotifications(context, config).apply {
        listen(
            PushHandlers(
                onToken = { tokens += it },
                onTokenFailure = { failures += it },
                onMessage = { message, completion -> messages += pushEventId(message.payload) to completion },
            ),
        )
    }

    private val processInfo = object : Binding<ProcessInfoState, ProcessInfo> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(ProcessInfoState.UNLOCKED)

        override fun create(state: ProcessInfoState, clauseId: String, log: CallLog): Entered<ProcessInfo> = when (state) {
            ProcessInfoState.UNLOCKED -> Entered.Ready(AndroidProcessInfo(context).recorded(log))
            ProcessInfoState.MEMORY_ACCOUNTED ->
                Entered.Unreachable(
                    "Android accounts no footprint to the process: no process-metric provider reads one",
                )
        }
    }

    @Test
    fun `the process read satisfies the ProcessInfo contract`() = verify(ProcessInfoContract, processInfo)

    @Test
    fun `the adapter speaks FCM`() {
        assertEquals(PUSH_KIND_FCM, adapter().kind)
    }

    @Test
    fun `a build that names no Firebase project answers a token failure`() {
        adapter().register()
        assertTrue(tokens.isEmpty())
        assertEquals(1, failures.size, "the ask is answered, with a failure: no silent push will arrive")
    }

    @Test
    fun `a rotated token reaches the core`() {
        adapter()
        @Suppress("DEPRECATION")
        SnapSyncMessagingService().onNewToken("fcm-token-1")
        assertEquals(listOf(PushToken("fcm-token-1")), tokens.toList())
    }

    @Test
    fun `a data message hands its eventId over and returns once the core releases it`() {
        adapter()
        val message = RemoteMessage.Builder("sender@fcm.googleapis.com").addData("eventId", "E1").build()
        val released = Thread {
            while (messages.isEmpty()) Thread.sleep(POLL_MILLIS)
            messages.single().second.complete()
        }.apply { start() }
        val started = TimeSource.Monotonic.markNow()
        SnapSyncMessagingService().onMessageReceived(message)
        released.join()
        assertEquals("E1", messages.single().first, "the payload is handed over whole; model's codec reads the event")
        assertTrue(
            started.elapsedNow().inWholeMilliseconds < AndroidPushNotifications.MESSAGE_BUDGET_MILLIS,
            "released, not timed out",
        )
    }

    @Test
    fun `a message the core never releases gives FCM’s thread back within its budget`() {
        adapter()
        val message = RemoteMessage.Builder("sender@fcm.googleapis.com").addData("eventId", "E2").build()
        val started = TimeSource.Monotonic.markNow()
        SnapSyncMessagingService().onMessageReceived(message)
        val waited = started.elapsedNow().inWholeMilliseconds
        assertTrue(
            waited in AndroidPushNotifications.MESSAGE_BUDGET_MILLIS until FCM_BUDGET_MILLIS,
            "returned after $waited ms",
        )
    }

    private companion object {
        const val POLL_MILLIS = 20L

        /** What FCM allows a message before it may reclaim the process. */
        const val FCM_BUDGET_MILLIS = 10_000L
    }
}

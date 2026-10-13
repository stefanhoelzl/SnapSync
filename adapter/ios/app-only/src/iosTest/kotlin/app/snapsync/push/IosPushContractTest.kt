package app.snapsync.push

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PushNotificationsContract
import app.snapsync.contracts.PushOs
import app.snapsync.contracts.PushState
import app.snapsync.contracts.PushUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * [IosPushNotifications] against the [PushNotificationsContract] in the simulator's test executable: APNs played
 * through the entries the app delegate calls — a token, a silent push with its fetch handler.
 *
 * Asking for a token needs a running app (`UIApplication`), which this host is not: that ask is the simulator app's.
 */
class IosPushContractTest {

    private val binding = object : Binding<PushState, PushUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosPushNotifications::class
        override val reaches = setOf(PushState.DELIVERING)
        override fun create(state: PushState, clauseId: String, log: CallLog): Entered<PushUnderTest> {
            if (state !in reaches) return Entered.Unreachable("asking APNs for a token needs a running app")
            val push = IosPushNotifications(Logger.withTag("contract"))
            val os = PushOs(
                issueToken = { push.deliverToken(it) },
                deliver = { data ->
                    var released = false
                    push.deliverMessage(HashMap<Any?, Any?>(data)) { released = true }
                    released
                },
            )
            return Entered.Ready(PushUnderTest(push.recorded(log), os))
        }
    }

    @Test
    fun `APNs deliveries satisfy the PushNotifications contract`() = verify(PushNotificationsContract, binding)
}

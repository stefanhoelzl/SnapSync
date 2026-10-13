@file:OptIn(CompositionOnly::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PushState
import app.snapsync.contracts.PushUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.push.IosPushNotifications

/**
 * The app's push adapter as its root holds it: APNs answers the app delegate, which forwards to this ONE instance, so a
 * binding that asks APNs for a token must use it — a second instance would never hear the answer.
 */
class AppPush(
    /** The root's adapter, the one the delegate forwards to. */
    val adapter: IosPushNotifications,
    /** The adapter the app's composition listened on: the root's, or a mock the launch's adapter choice put there. */
    val composed: () -> PushNotifications,
    /** The handlers the composition listens with, built afresh. */
    val appHandlers: () -> PushHandlers,
)

/**
 * The real [IosPushNotifications] in the simulator app, which carries no `aps-environment` entitlement — a build that
 * names no push project: APNs answers the app's ask with a failure. The clause listens on the app's own adapter, so
 * the disposal hands the app back its handlers wherever the app listens there.
 */
class SimAppPushBinding(private val push: AppPush) : Binding<PushState, PushUnderTest> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosPushNotifications::class
    override val reaches = setOf(PushState.NO_PUSH_PROJECT)

    override fun create(state: PushState, clauseId: String, log: CallLog): Entered<PushUnderTest> {
        if (state !in reaches) return Entered.Unreachable("the simulator app is signed with no push entitlement")
        return Entered.Ready(PushUnderTest(push.adapter.recorded(log))) {
            if (push.composed() === push.adapter) push.adapter.inbound(push.appHandlers())
        }
    }
}

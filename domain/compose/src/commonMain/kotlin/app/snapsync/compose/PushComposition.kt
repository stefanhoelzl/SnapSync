package app.snapsync.compose

import app.snapsync.model.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.ports.PushTokenSource

/**
 * The push registration's three ports (capability `push-registration`), one [AppPorts] field because they are one
 * need: [publisher] writes this device's registration (the `PUT` of its APNs token — built into `PushRegistration`
 * here rather than by a shell, which once re-entered it through a `registerPush` lambda the world bound to a
 * counter), [tokens] is what the OS delivered and in which environment, and [record] is the last registration the
 * backend accepted, against which a delivered token is compared. [record] is required: a composition that
 * defaulted it to an empty record would publish at every launch — the cost it exists to remove — and nothing would
 * say so.
 */
class PushPorts(
    val publisher: PushTokenPublisher,
    val tokens: PushTokenSource,
    val record: PushRegistrationRecord,
)

/**
 * The device's push registration (capability `push-registration`) over the app's ports — built in `compose/`
 * rather than by a shell, so the launch/rotation collector and the join's re-registration are the same instance
 * on every composition and the world exercises the real one. It used to be built by the iOS root and reached
 * the core through a `registerPush` lambda the world bound to a counter.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun pushRegistrationFor(ports: AppPorts): PushRegistration =
    PushRegistration(ports.push.publisher, ports.push.record, ports.deviceIdentity)

/**
 * Re-PUT the delivered APNs token on join, whatever the last-registered record holds (capability
 * `push-registration`: a join publishes unconditionally); a no-op before the OS has delivered one.
 */
internal suspend fun PushRegistration.reRegister(ports: AppPorts) {
    ports.push.tokens.token.value?.let { register(ApnsPushToken(it, ports.push.tokens.env)) }
}

package app.snapsync.compose

import app.snapsync.model.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.ports.PushTokenSource
import app.snapsync.services.backend.PushTokenPublisher

/**
 * The push registration's two ports (capability `receiving-photos`), one [AppPorts] field because they are one
 * need: [tokens] is what the OS delivered and in which environment, and [record] is the last registration the
 * backend accepted, against which a delivered token is compared. The publish itself is a backend service composed
 * over the app's one authenticated backend. [record] is required: a composition that defaulted it to an empty
 * record would publish at every launch — the cost it exists to remove — and nothing would say so.
 */
class PushPorts(
    val tokens: PushTokenSource,
    val record: PushRegistrationRecord,
)

/**
 * The device's push registration (capability `receiving-photos`) over the app's ports — built in `compose/`
 * rather than by a shell, so the launch/rotation collector and the join's re-registration are the same instance
 * on every composition and the world exercises the real one. It used to be built by the iOS root and reached
 * the core through a `registerPush` lambda the world bound to a counter.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun pushRegistrationFor(ports: AppPorts, publisher: PushTokenPublisher): PushRegistration =
    PushRegistration(publisher, ports.push.record, ports.deviceIdentity)

/**
 * Re-PUT the delivered APNs token on join, whatever the last-registered record holds (capability
 * `receiving-photos`: a join publishes unconditionally); a no-op before the OS has delivered one.
 */
internal suspend fun PushRegistration.reRegister(ports: AppPorts) {
    ports.push.tokens.token.value?.let { register(ApnsPushToken(it, ports.push.tokens.env)) }
}

package app.snapsync.compose

import app.snapsync.model.PushEndpoint
import app.snapsync.feature.push.PushRegistration
import app.snapsync.services.backend.PushTokenPublisher

/**
 * The device's push registration (capability `receiving-photos`) over the app's services — built in `compose/`
 * rather than by a shell, so the launch/rotation collector and the join's re-registration are the same instance
 * on every composition and the world exercises the real one. It used to be built by the iOS root and reached
 * the core through a `registerPush` lambda the world bound to a counter.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun pushRegistrationFor(services: AppServices, publisher: PushTokenPublisher): PushRegistration =
    PushRegistration(publisher, services.pushRecord, services.deviceIdentity)

/**
 * Re-PUT the delivered push token on join, whatever the last-registered record holds (capability
 * `receiving-photos`: a join publishes unconditionally); a no-op before the OS has delivered one.
 */
internal suspend fun PushRegistration.reRegister(services: AppServices) {
    services.pushTokens.token.value?.let { register(PushEndpoint(services.pushTokens.kind, it, services.pushTokens.env)) }
}

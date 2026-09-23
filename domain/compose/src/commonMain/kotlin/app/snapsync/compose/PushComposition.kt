package app.snapsync.compose

import app.snapsync.feature.push.ApnsPushToken
import app.snapsync.feature.push.PushRegistration

/**
 * The device's push registration (capability `push-registration`) over the app's ports — built in `compose/`
 * rather than by a shell, so the launch/rotation collector and the join's re-registration are the same instance
 * on every composition and the world exercises the real one. It used to be built by the iOS root and reached
 * the core through a `registerPush` lambda the world bound to a counter.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun pushRegistrationFor(ports: AppPorts): PushRegistration =
    PushRegistration(ports.pushHttpClient, ports.backendHost, identity = ports.deviceIdentity)

/** Re-PUT the delivered APNs token on join; a no-op before the OS has delivered one. */
internal suspend fun PushRegistration.reRegister(ports: AppPorts) {
    ports.pushTokens.token.value?.let { register(ApnsPushToken(it, ports.pushTokens.env)) }
}

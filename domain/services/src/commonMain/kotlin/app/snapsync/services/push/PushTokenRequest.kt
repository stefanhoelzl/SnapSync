package app.snapsync.services.push

import app.snapsync.ports.PushNotifications

/**
 * Asking the platform for this device's push token — cheap and idempotent, and the only way to learn a rotated one, so
 * the app asks at every launch and at every foreground entry. The answer arrives as a delivery on the push port.
 */
class PushTokenRequest(private val push: PushNotifications) {
    fun request() = push.register()
}

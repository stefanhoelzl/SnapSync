package app.snapsync.ports

/**
 * **The platform's own stable device id**, where it offers one — one external system, and nothing decided here.
 * iOS offers none this app may use (and the JVM none), so their adapter answers `null`; a platform that does
 * (Android: an id derived from `ANDROID_ID`) answers it, and the persisted identity uses it in place of a random
 * one when it has to mint.
 *
 * Its promises are the port contract `PlatformDeviceIdContract`: an offered id is stable and has the device-id shape,
 * bound live on the Android emulator; no id is `null`, bound on the JVM.
 */
fun interface PlatformDeviceId : Port {
    fun stableId(): String?
}

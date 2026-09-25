package app.snapsync.ports

/**
 * **The platform's own stable device id**, where it offers one — one external system, and nothing decided here.
 * iOS offers none this app may use (and the JVM none), so their adapter answers `null`; a platform that does
 * (Android: an id derived from `ANDROID_ID`) answers it, and the persisted identity uses it in place of a random
 * one when it has to mint.
 *
 * No contract yet: the only implementation is the constant `null`, and a clause needs a real implementation on a
 * host to run against (`ContractCoverageTest`). The identity service's test covers "`null` ⇒ random" with a stub;
 * the contract lands with the first adapter that answers anything.
 */
fun interface PlatformDeviceId {
    fun stableId(): String?
}

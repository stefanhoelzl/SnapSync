package app.snapsync.model

/**
 * The store could not be read, or the write that had to persist a value was refused. Thrown by the identity and
 * attestation services where the old throwing port threw; never mistaken for absence.
 */
class SecureStoreUnavailable(val detail: String) :
    IllegalStateException("secure store unavailable ($detail): protected data is not accessible")

/**
 * The addressed item holds no value and this caller may not mint one.
 *
 * Distinct from [SecureStoreUnavailable] ("I could not look") and from a silent absence: it means the
 * lookup succeeded and found nothing, in a process whose right to generate an identity is withheld.
 * The upload extension is that process — it cannot distinguish "this device has no identity yet"
 * from "the app's identity is not reachable from here", and guessing produces a second identity that
 * orphans the device's byte partition and makes its own uploads read as another member's.
 *
 * Callers treat it exactly as they treat [SecureStoreUnavailable]: skip the cycle, do no work, retry
 * next invocation. The app resolves the identity on every launch, so the wait is bounded.
 */
class DeviceIdentityAbsent :
    IllegalStateException("device identity absent and this process may not mint one")

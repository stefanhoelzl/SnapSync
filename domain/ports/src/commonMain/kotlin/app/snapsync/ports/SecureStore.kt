package app.snapsync.ports

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.WriteOutcome

/**
 * **The platform's protected small-value store** — one external system (on iOS the Keychain), and nothing decided
 * here. Each call addresses one [SecureSlot]; which items exist, and what their absence means, are the services'
 * (`PersistedDeviceIdentity`, `AttestState`, the album-map migration in `:domain:services`).
 *
 * It keeps a value confidential at rest, **outlives the app install**, and stays readable while the device is
 * locked after its first unlock. On iOS it is the only module permitted to touch `SecItem*` (`docs/architecture.md`).
 *
 * Synchronous: the Keychain is, and so is every platform this port is meant for.
 *
 * ## The three-state read is the point of the seam
 *
 * A protected store answers a read with a *failure*, and the fatal historical mistake was mapping every failure to
 * "no value stored": the device id then **minted a new UUID** on a locked device (the build-297 crash; had the
 * write succeeded, a *new identity*, orphaning the device's byte partition and its ledger). So "absent" and "I could
 * not look" are different answers, and [SecureStoreRead] refuses to conflate them (`docs/architecture.md`,
 * "Absence is never silent").
 *
 * Writes answer a [WriteOutcome] instead of throwing: a service decides what a refused write means (the device id
 * is then unavailable and never used unsaved; an attestation token is not accepted). A [write] may **replace by
 * delete-then-add**, so after a refused write the old value may be gone.
 */
interface SecureStore {

    /** Read the item: its value and how it is currently protected. */
    fun read(slot: SecureSlot): SecureStoreRead

    /** Persist [value], replacing any existing item, under the protection this store requires. */
    fun write(slot: SecureSlot, value: String): WriteOutcome

    /**
     * Upgrade the *existing* item to the required protection in place, **preserving its value byte for byte**.
     * Never deletes-and-re-adds and never mints: a changed device id would orphan this device's partition.
     *
     * Best-effort by contract: a store that cannot upgrade right now keeps the item it has, answers the failure,
     * and the upgrade is retried on the next read. Failing the read instead would turn a healthy legacy device into
     * a broken one.
     */
    fun migrateProtection(slot: SecureSlot): WriteOutcome

    /** Delete the item. Deleting an absent item is [WriteOutcome.Ok]. */
    fun delete(slot: SecureSlot): WriteOutcome
}

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

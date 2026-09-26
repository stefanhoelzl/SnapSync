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

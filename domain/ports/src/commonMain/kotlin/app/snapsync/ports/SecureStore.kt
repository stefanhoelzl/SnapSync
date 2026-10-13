package app.snapsync.ports

/**
 * **The platform's protected small-value store** — one external system (on iOS the Keychain), and nothing decided
 * here. Each call addresses one [SecureSlot]; which items exist, and what their absence means, are the services'
 * (`PersistedDeviceIdentity`, `AttestState`, `EventKeys` in `:domain:services`).
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
interface SecureStore : Port {

    /** Read the item: its value and how it is currently protected. */
    fun read(slot: SecureSlot): SecureStoreRead

    /** Persist [value], replacing any existing item, under the protection this store requires. */
    fun write(slot: SecureSlot, value: String): WriteOutcome

    /** Delete the item. Deleting an absent item is [WriteOutcome.Ok]. */
    fun delete(slot: SecureSlot): WriteOutcome
}

/**
 * One addressed item of the `SecureStore` port: a (service, account) pair, and whether it lives in the store's
 * **shared** place — on iOS the App-Group Keychain access group both processes are entitled to — or is searched
 * without naming one ([shared] `false`: whatever groups this process may read; a write lands where the platform
 * chooses from the writing build's entitlements).
 *
 * Unscoped placement is how the app and the extension once came to hold two different device ids, so every
 * unscoped slot is a pinned inventory (`RuntimeIdentityTest`), and new items name their place.
 */
data class SecureSlot(val service: String, val account: String, val shared: Boolean)

/** The three answers a [SecureStore] read can give. `Absent` and `Unavailable` are **not** the same. */
sealed interface SecureStoreRead {

    /** The item exists. [protection] is how the store reports it is currently protected. */
    data class Found(val value: String, val protection: StoredProtection) : SecureStoreRead

    /** The item genuinely does not exist — the only state that may mint. */
    data object Absent : SecureStoreRead

    /**
     * The read failed. Typically because protected data is unavailable: the device has not been
     * unlocked since boot. Says nothing whatsoever about whether an item exists.
     *
     * [detail] is an **opaque diagnostic** the adapter formats for a device log, and nothing here
     * classifies it. That is deliberate and structural: the platform's own error numbering is the
     * adapter's, a code invites a `when` that would re-import it, and every decision in this file
     * reads the three-state shape instead. Decision record: `changes/…/reshape-keychain-port` (D3).
     */
    data class Unavailable(val detail: String) : SecureStoreRead
}

/**
 * How a stored item is protected, as much as the store is willing to say — the platform-free
 * replacement for a protection-class identifier crossing this seam.
 *
 * Three members rather than a boolean because [RESTRICTED] and [UNREPORTED] are different facts even
 * though they drive the same action. Collapsing them would be a collapse nobody chose (law
 * `docs/architecture.md`, "Absence is never silent").
 */
enum class StoredProtection {

    /**
     * Readable by background work on a **locked** device, once it has been unlocked since boot. This
     * is what every SnapSync item requires: background work runs while the device is idle, which
     * usually means locked, and that is precisely when these values are read.
     */
    BACKGROUND_READABLE,

    /**
     * Stored under *some other* protection — deliberately a "not the required one" answer, not a
     * claim about which. Reported, never repaired: every build since v0.1 writes [BACKGROUND_READABLE],
     * so this is a diagnostic. If a device log needs to know which class an item was actually filed
     * under, the adapter that read it is where that is recorded.
     */
    RESTRICTED,

    /** The store did not report how the item is protected. A different fact from [RESTRICTED], logged as such. */
    UNREPORTED,
}

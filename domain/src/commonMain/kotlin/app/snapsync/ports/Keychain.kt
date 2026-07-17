package app.snapsync.ports

/**
 * The Keychain seam (capability `architecture-guards`: this module is the **only** place in the repo
 * that may touch `SecItem*` — a guard in `:test:architecture` enforces it).
 *
 * The point of the seam is the three-state [KeychainRead]. The iOS Keychain answers a read with a
 * status code, and the *fatal* historical mistake was mapping every non-success status to "no value
 * stored":
 *
 * - the device id then **minted a new UUID** on a locked device and tried to persist it — which fails
 *   for the same reason the read did, so `SecItemAdd` threw and the process aborted (the build-297
 *   crash). Had the write instead succeeded, the device would have silently acquired a *new identity*,
 *   orphaning its byte-store partition and its ledger.
 * - the event config then read as "no event joined", which the upload extension's reconciliation takes
 *   to mean **the device left the event** — clearing its join marker on every locked wake.
 *
 * So "absent" and "I could not look" are different answers, and this type refuses to conflate them.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
interface Keychain {

    /** Read the item: its value and the accessibility class it is currently stored under. */
    fun read(): KeychainRead

    /** Persist [value], replacing any existing item, under the required accessibility class. */
    fun write(value: String)

    /**
     * Upgrade the *existing* item's accessibility class in place, **preserving its value byte for
     * byte**. Never deletes-and-re-adds and never mints: a changed device id would orphan this
     * device's `/files/devices/<deviceId>/` partition and its ledger.
     */
    fun migrateAccessibility()

    /** Delete the item. Deleting an absent item is a no-op, not an error. */
    fun delete()
}

/** The three answers a Keychain read can give. `Absent` and `Unavailable` are **not** the same. */
sealed interface KeychainRead {

    /** The item exists. [accessibility] is the class it is stored under (null if unreported). */
    data class Found(val value: String, val accessibility: String?) : KeychainRead

    /** The item genuinely does not exist (`errSecItemNotFound`) — the only state that may mint. */
    data object Absent : KeychainRead

    /**
     * The read failed. Typically `errSecInteractionNotAllowed` (-25308): protected data is unavailable
     * because the device has not been unlocked since boot. Says nothing about whether an item exists.
     */
    data class Unavailable(val status: Int) : KeychainRead
}

/** The Keychain could not be read. Caught at the composition roots; never mistaken for absence. */
class KeychainUnavailable(val status: Int) :
    IllegalStateException("keychain unavailable (status=$status): protected data is not accessible")

/**
 * The mint-once-then-read core, shared by every Keychain-backed store and tested in `commonTest` (so
 * it runs on JVM **and** `iosSimulatorArm64`). Pure: the platform supplies the effects.
 *
 * - [KeychainRead.Found] → return the stored value verbatim, upgrading its accessibility class first
 *   if it predates the current requirement ([needsMigration]). The value is never rewritten.
 * - [KeychainRead.Absent] → mint, persist, return. **The only branch that generates.**
 * - [KeychainRead.Unavailable] → throw [KeychainUnavailable]. Never mints, never writes.
 */
fun resolveOrMint(
    keychain: Keychain,
    requiredAccessibility: String,
    generate: () -> String,
): String = when (val read = keychain.read()) {
    is KeychainRead.Found -> {
        if (needsMigration(read.accessibility, requiredAccessibility)) keychain.migrateAccessibility()
        read.value
    }
    KeychainRead.Absent -> generate().also { keychain.write(it) }
    is KeychainRead.Unavailable -> throw KeychainUnavailable(read.status)
}

/**
 * Read an existing value without ever minting: `null` when the item is genuinely [KeychainRead.Absent],
 * throwing [KeychainUnavailable] when it could not be read. Used by stores (the event config) that have
 * nothing to mint — they persist only what a user action produced.
 */
fun readExisting(keychain: Keychain, requiredAccessibility: String): String? =
    when (val read = keychain.read()) {
        is KeychainRead.Found -> {
            if (needsMigration(read.accessibility, requiredAccessibility)) keychain.migrateAccessibility()
            read.value
        }
        KeychainRead.Absent -> null
        is KeychainRead.Unavailable -> throw KeychainUnavailable(read.status)
    }

/**
 * Whether a stored item predates the current accessibility requirement and must be upgraded in place.
 *
 * Migration is not optional book-keeping: the Keychain **survives app uninstall** (that is the
 * reinstall-stability of capability `device-identity`) and the device id is written exactly once, at
 * mint. Without an in-place upgrade, a device provisioned by an older build would keep its
 * locked-unreadable item **forever** — no reinstall, no app update, nothing would ever rewrite it.
 */
fun needsMigration(current: String?, required: String): Boolean = current != required

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
 * The order below is normative, and each step exists because the one above it was once skipped:
 *
 * - [KeychainRead.Found] → return the stored value verbatim, upgrading its accessibility class first
 *   if it predates the current requirement ([needsMigration]). The value is never rewritten.
 * - [KeychainRead.Absent] → consult [readLegacy] **before** minting. A value found there is adopted
 *   verbatim ([KeychainResolution.Adopted]); only a second absence mints. Callers that pass no
 *   [readLegacy] mint straight away, which is correct for items with no legacy placement.
 * - [KeychainRead.Unavailable] → throw [KeychainUnavailable]. Never mints, never writes.
 *
 * Unavailability outranks both absence and adoption, on **either** read. "I could not look" is not
 * "there is nothing there", and conflating them is what mints a duplicate identity on a locked
 * device — the failure this ordering is built against.
 */
fun resolveOrMint(
    keychain: Keychain,
    requiredAccessibility: String,
    onResolution: (KeychainResolution) -> Unit = {},
    readLegacy: () -> KeychainRead = { KeychainRead.Absent },
    generate: () -> String,
): String = when (val read = keychain.read()) {
    is KeychainRead.Found -> {
        val migrated = needsMigration(read.accessibility, requiredAccessibility)
        if (migrated) keychain.migrateAccessibility()
        onResolution(KeychainResolution.Found(read.accessibility, migrated))
        read.value
    }

    // Absence in the addressed item is NOT yet permission to mint: an older build may have written
    // the value somewhere this query does not reach (see [KeychainResolution] for how that happens).
    // Consult [readLegacy] first and adopt whatever it finds, verbatim.
    KeychainRead.Absent -> when (val legacy = readLegacy()) {
        is KeychainRead.Found -> legacy.value.also {
            keychain.write(it)
            onResolution(KeychainResolution.Adopted)
        }

        KeychainRead.Absent -> generate().also {
            keychain.write(it)
            onResolution(KeychainResolution.Minted)
        }

        // "I could not look" on the LEGACY read is as disqualifying as on the primary one: minting
        // here would generate a second identity for a device that may already have one, which is the
        // unrecoverable outcome this whole ordering exists to prevent. Defer instead.
        is KeychainRead.Unavailable -> throw KeychainUnavailable(legacy.status)
    }

    is KeychainRead.Unavailable -> throw KeychainUnavailable(read.status)
}

/**
 * Which branch of [resolveOrMint] produced the returned value — reported to the caller so it can be
 * *observed*, not merely trusted.
 *
 * This exists because a silent mint is indistinguishable from a successful read at every layer above
 * this one, and the difference is the whole ballgame: a second mint hands the process a **new
 * identity**, orphaning its `/files/devices/<deviceId>/` partition and making its own uploads read as
 * another contributor's (`DownloadController` skips an asset only when `asset.deviceId ==
 * myDeviceId`, so a re-minted id re-downloads and re-imports every photo the device itself
 * contributed — one duplicate per photo, in the user's own library).
 *
 * That is not hypothetical: on 2026-07-20 an SE2 ran for nine hours with the app on one id and the
 * upload extension on another, and **nothing anywhere logged either one** — the symptom reached the
 * screen as an indefinite "pending" and reached the library as duplicated photos. [accessibility] is
 * carried on [Found] because a legacy protection class is the leading suspect for a read that returns
 * `errSecItemNotFound` against an item that does exist.
 */
sealed interface KeychainResolution {

    /** An existing item was read. [migrated] = its accessibility class was upgraded in place. */
    data class Found(val accessibility: String?, val migrated: Boolean) : KeychainResolution

    /**
     * The addressed item was absent, but a value was found by the legacy read and adopted verbatim.
     *
     * This is the repair branch for an item an older build placed elsewhere. Keychain placement is
     * the **access group**, and when no group is named the platform picks one at *write* time from
     * the signing entitlements then in force — so an item's group is a fact about the build that
     * wrote it, not about this contract. A build signed against a wildcard grant (every Apple
     * *development* profile grants `<team>.*`, which is not a writable group name) falls back to
     * each process's own `application-identifier` group, and the app and the extension then hold
     * **different items** while both reads report success.
     */
    data object Adopted : KeychainResolution

    /** No item existed anywhere, so one was generated and persisted. The only new-identity branch. */
    data object Minted : KeychainResolution
}

/**
 * The addressed item holds no value and this caller may not mint one.
 *
 * Distinct from [KeychainUnavailable] ("I could not look") and from a silent absence: it means the
 * lookup succeeded and found nothing, in a process whose right to generate an identity is withheld.
 * The upload extension is that process — it cannot distinguish "this device has no identity yet"
 * from "the app's identity is not reachable from here", and guessing produces a second identity that
 * orphans the device's byte partition and makes its own uploads read as another member's.
 *
 * Callers treat it exactly as they treat [KeychainUnavailable]: skip the cycle, do no work, retry
 * next invocation. The app resolves the identity on every launch, so the wait is bounded.
 */
class DeviceIdentityAbsent :
    IllegalStateException("device identity absent and this process may not mint one")

/**
 * Read an existing value without ever minting: `null` when the item is genuinely [KeychainRead.Absent],
 * throwing [KeychainUnavailable] when it could not be read. Used by stores (the event config) that have
 * nothing to mint — they persist only what a user action produced.
 *
 * Absence: null means **absent, and only absent**. This function is where that separation is
 * enforced for every Keychain-backed store: an unreadable item throws rather than answering empty,
 * so no caller can mistake "the device is locked" for "this device never had a token". It is the
 * reference implementation of the rule, not an exception to it (spec `module-architecture`,
 * "Absence is never silent").
 */
fun readExisting(
    keychain: Keychain,
    requiredAccessibility: String,
    onResolution: (KeychainResolution) -> Unit = {},
): String? =
    when (val read = keychain.read()) {
        is KeychainRead.Found -> {
            val migrated = needsMigration(read.accessibility, requiredAccessibility)
            if (migrated) keychain.migrateAccessibility()
            onResolution(KeychainResolution.Found(read.accessibility, migrated))
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

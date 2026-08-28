package app.snapsync.ports

/**
 * **One addressed place to keep one small value**, so that it is confidential at rest, **outlives the
 * app install**, and stays readable while the device is locked. Four things in this app need exactly
 * that and nothing more: the device id, the attestation token, its `keyId`, and — for one last
 * migration — the legacy event-album map.
 *
 * An instance addresses **one** value, not a keyspace: a store is constructed per item, the way
 * `ConfigStore` is constructed for one config. Which item an instance addresses, and how a value is
 * protected there, are the adapter's business and never cross this seam.
 *
 * It is named for that need rather than for the technology satisfying it (law `module-architecture`,
 * "Ports are the I/O boundary named for the need"). On iOS the implementation is the Keychain, which
 * is the only module in the repo permitted to touch `SecItem*` (capability `architecture-guards`);
 * that is a binding note, not this contract. Decision record:
 * `changes/…/reshape-keychain-port` (D2).
 *
 * ## The three-state read is the point of the seam
 *
 * A protected store answers a read with a *failure*, and the fatal historical mistake was mapping
 * every failure to "no value stored":
 *
 * - the device id then **minted a new UUID** on a locked device and tried to persist it — which fails
 *   for the same reason the read did, so the write threw and the process aborted (the build-297
 *   crash). Had the write instead succeeded, the device would have silently acquired a *new identity*,
 *   orphaning its byte-store partition and its ledger.
 * - the event config then read as "no event joined", which the upload extension's reconciliation takes
 *   to mean **the device left the event** — clearing its join marker on every locked wake.
 *
 * So "absent" and "I could not look" are different answers, and [SecureStoreRead] refuses to conflate
 * them (law `module-architecture`, "Absence is never silent"). Decision record:
 * `changes/archive/…-fix-locked-device-keychain-access`.
 */
interface SecureStore {

    /** Read the item: its value and how it is currently protected. */
    fun read(): SecureStoreRead

    /** Persist [value], replacing any existing item, under the protection this store requires. */
    fun write(value: String)

    /**
     * Upgrade the *existing* item to the required protection in place, **preserving its value byte
     * for byte**. Never deletes-and-re-adds and never mints: a changed device id would orphan this
     * device's `/files/devices/<deviceId>/` partition and its ledger.
     *
     * Best-effort by contract: a store that cannot be upgraded right now keeps the item it has and is
     * retried on the next read. Failing the read instead would turn a healthy legacy device into a
     * broken one.
     */
    fun migrateProtection()

    /** Delete the item. Deleting an absent item is a no-op, not an error. */
    fun delete()
}

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
 * `module-architecture`, "Absence is never silent").
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
     * claim about which. The item is upgraded in place; if a device log needs to know which class an
     * item was actually filed under, the adapter that read it is where that is recorded.
     */
    RESTRICTED,

    /** The store did not report how the item is protected. Treated as [RESTRICTED] for the upgrade. */
    UNREPORTED,
}

/** The store could not be read. Caught at the composition roots; never mistaken for absence. */
class SecureStoreUnavailable(val detail: String) :
    IllegalStateException("secure store unavailable ($detail): protected data is not accessible")

/**
 * The mint-once-then-read core, shared by every [SecureStore]-backed store and tested in `commonTest`
 * (so it runs on JVM **and** `iosSimulatorArm64`). Pure: the platform supplies the effects.
 *
 * The order below is normative, and each step exists because the one above it was once skipped:
 *
 * - [SecureStoreRead.Found] → return the stored value verbatim, upgrading its protection first if it
 *   is not what the store requires ([needsMigration]). The value is never rewritten.
 * - [SecureStoreRead.Absent] → consult [readLegacy] **before** minting. A value found there is adopted
 *   verbatim ([SecureStoreResolution.Adopted]); only a second absence mints. Callers that pass no
 *   [readLegacy] mint straight away, which is correct for items with no legacy placement.
 * - [SecureStoreRead.Unavailable] → throw [SecureStoreUnavailable]. Never mints, never writes.
 *
 * Unavailability outranks both absence and adoption, on **either** read. "I could not look" is not
 * "there is nothing there", and conflating them is what mints a duplicate identity on a locked
 * device — the failure this ordering is built against.
 */
fun resolveOrMint(
    store: SecureStore,
    onResolution: (SecureStoreResolution) -> Unit = {},
    readLegacy: () -> SecureStoreRead = { SecureStoreRead.Absent },
    generate: () -> String,
): String = when (val read = store.read()) {
    is SecureStoreRead.Found -> {
        val migrated = needsMigration(read.protection)
        if (migrated) store.migrateProtection()
        onResolution(SecureStoreResolution.Found(read.protection, migrated))
        read.value
    }

    // Absence in the addressed item is NOT yet permission to mint: an older build may have written
    // the value somewhere this query does not reach (see [SecureStoreResolution] for how that
    // happens). Consult [readLegacy] first and adopt whatever it finds, verbatim.
    SecureStoreRead.Absent -> when (val legacy = readLegacy()) {
        is SecureStoreRead.Found -> legacy.value.also {
            store.write(it)
            onResolution(SecureStoreResolution.Adopted)
        }

        SecureStoreRead.Absent -> generate().also {
            store.write(it)
            onResolution(SecureStoreResolution.Minted)
        }

        // "I could not look" on the LEGACY read is as disqualifying as on the primary one: minting
        // here would generate a second identity for a device that may already have one, which is the
        // unrecoverable outcome this whole ordering exists to prevent. Defer instead.
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(legacy.detail)
    }

    is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
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
 * screen as an indefinite "pending" and reached the library as duplicated photos. [Found] carries the
 * item's protection because a legacy protection is the leading suspect for a read that reports
 * *absent* against an item that does exist.
 */
sealed interface SecureStoreResolution {

    /** An existing item was read. [migrated] = its protection was upgraded in place. */
    data class Found(val protection: StoredProtection, val migrated: Boolean) : SecureStoreResolution

    /**
     * The addressed item was absent, but a value was found by the legacy read and adopted verbatim.
     *
     * This is the repair branch for an item an older build placed elsewhere. Placement is a property
     * of the *build that wrote the item* wherever the platform is allowed to choose it at write time
     * from the signing entitlements then in force — so two processes of one app can end up holding
     * **different items** while both reads report success. That is not a hypothetical either: it is
     * the 2026-07-20 split identity, and the adapter's own KDoc records the mechanism.
     */
    data object Adopted : SecureStoreResolution

    /** No item existed anywhere, so one was generated and persisted. The only new-identity branch. */
    data object Minted : SecureStoreResolution
}

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

/**
 * Read an existing value without ever minting: `null` when the item is genuinely
 * [SecureStoreRead.Absent], throwing [SecureStoreUnavailable] when it could not be read. Used by
 * stores (the attestation token) that have nothing to mint — they persist only what a backend or a
 * user action produced.
 *
 * Absence: null means **absent, and only absent**. This function is where that separation is
 * enforced for every [SecureStore]-backed store: an unreadable item throws rather than answering
 * empty, so no caller can mistake "the device is locked" for "this device never had a token". It is
 * the reference implementation of the rule, not an exception to it (spec `module-architecture`,
 * "Absence is never silent").
 */
fun readExisting(
    store: SecureStore,
    onResolution: (SecureStoreResolution) -> Unit = {},
): String? =
    when (val read = store.read()) {
        is SecureStoreRead.Found -> {
            val migrated = needsMigration(read.protection)
            if (migrated) store.migrateProtection()
            onResolution(SecureStoreResolution.Found(read.protection, migrated))
            read.value
        }
        SecureStoreRead.Absent -> null
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
    }

/**
 * Whether a stored item must be upgraded in place to the protection this store requires.
 *
 * Migration is not optional book-keeping, and the argument is a property of **this seam**, not of any
 * one platform: a [SecureStore] **outlives the app install** by contract (that is the
 * reinstall-stability capability `device-identity` depends on), and the device id is written exactly
 * once, at mint. Nothing in the device's remaining lifetime will therefore ever rewrite the item — no
 * reinstall, no app update, no later write of any kind — so an item a pre-fix build filed as
 * unreadable-in-background stays that way **forever** unless the read path upgrades it.
 */
fun needsMigration(protection: StoredProtection): Boolean =
    protection != StoredProtection.BACKGROUND_READABLE

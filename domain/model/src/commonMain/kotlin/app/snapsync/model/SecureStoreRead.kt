package app.snapsync.model

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
     * claim about which. The item is upgraded in place; if a device log needs to know which class an
     * item was actually filed under, the adapter that read it is where that is recorded.
     */
    RESTRICTED,

    /** The store did not report how the item is protected. Treated as [RESTRICTED] for the upgrade. */
    UNREPORTED,
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

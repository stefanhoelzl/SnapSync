package app.snapsync.model

/**
 * Whether an asset this device created still exists in the photo library (capability `photo-download`).
 *
 * Three-valued, and the third value is the point. An import that recorded its created asset but never
 * recorded a confirmation leaves a row that must be adjudicated rather than re-imported — and the
 * adjudication has to be able to say *"I do not know"*, because for most photo-access grants a miss is
 * not evidence of absence:
 *
 * - under a **partial** grant a fetch sees only the user's selection, and an asset created under a full
 *   grant before a downgrade is real but invisible (app-created assets join the selection at creation
 *   time only — measured, capability `limited-photo-access`);
 * - with **no** usable grant a query returns nothing for assets that plainly exist.
 *
 * Reporting [ABSENT] in either case would clear a live marker, import a second copy, and orphan the
 * first — which is the defect this vocabulary exists to prevent. So [ABSENT] is a claim only a source
 * that can see the whole library may make; everything else answers [UNKNOWN] and the row waits.
 */
enum class AssetPresence {
    /** The asset exists. Safe to settle the row against the marker it already holds. */
    PRESENT,

    /**
     * The asset does not exist, from a source that can see the whole library. The marker is stale — clear
     * it and import. (Cannot distinguish "the user deleted it" from "the commit never landed"; the
     * capability accepts a single re-import for that, because the alternative loses the photo silently.)
     */
    ABSENT,

    /** Not answerable from what this source can see. Change nothing; the row is adjudicated again later. */
    UNKNOWN,
}

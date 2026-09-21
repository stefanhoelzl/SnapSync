package app.snapsync.ports

import app.snapsync.model.Candidate
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy

/**
 * What the upload cycle reads from the photo library: the full-enumeration walk and the id-scoped resolve of
 * ledger keys (capability `ios-url-session-upload`, "Ledger keys resolve to uploadable resources").
 *
 * Its own port rather than two members of [BackgroundTransfer], because neither read is a transport concern:
 * on every tier both are the same PhotoKit fetches, and only the transfer lifecycle differs. Each composition
 * root binds it **once**, and no transport implements or forwards it. The partial-grant read discipline wraps
 * this port, not the transport (`SelectionScopedDiscovery`, capability `limited-photo-access`).
 *
 * Not [CandidateSource], although once the change-token cursor was removed the walk became nearly the same
 * read: this port's walk also says whether it is **authoritative for deletion** ([Discovery.fullEnumeration]),
 * which a count has no use for, and it is what the partial-grant read discipline wraps.
 */
interface UploadDiscovery {

    /**
     * Enumerate the library's candidate assets — **every** walk is a full enumeration; there is no persisted
     * cursor (capability `ios-photokit-upload`, "In-extension discovery by full enumeration").
     *
     * [policy] carries the membership's capture-date range (capability `photo-selection-policy`). The walk
     * SHALL be scoped by it — walking the whole library costs one synchronous platform round-trip per asset.
     * An implementation MAY return assets outside the range (the cycle filters), but MUST NOT omit any inside
     * it: what this returns is also the walk's **presence** set, and an in-window asset it omits has its rows
     * deleted as departed (capability `sync-ledger`).
     */
    suspend fun discover(policy: SelectionPolicy): Discovery

    /**
     * Resolve ledger [keys] to uploadable [Resource]s — **id-scoped, never a walk**.
     *
     * This is what lets the ledger be the cycle's source of work (capability `sync-ledger`). A row records
     * that a resource needs uploading, but it cannot carry the platform handle `createJob` requires, so a
     * producer enqueueing from the ledger asks for exactly the keys it intends to send. A key is
     * `<assetId>-<role>.<ext>`, so an implementation has everything it needs to fetch those assets by
     * identifier and pick the matching resource.
     *
     * **Partial-tolerant, and that is the contract, not a convenience.** A key whose asset has left the
     * library resolves to nothing — the caller learns the asset departed, which is a different fact from
     * an upload failing, and the two must not be collapsed (`module-architecture`, "Absence is never
     * silent"). An implementation MUST NOT throw for a missing key and MUST NOT substitute another
     * resource for it.
     *
     * Each returned resource's `filename` is the key it was resolved for, so a caller can pair them back
     * up without a second lookup.
     */
    suspend fun resourcesFor(keys: Set<String>): List<Resource>
}

/**
 * One walk's candidates, and whether the walk is **authoritative for deletion**.
 */
class Discovery(
    /**
     * The assets the platform returned — **candidates**, not yet admitted. Each carries cheap facts and
     * fetches its own resources on demand, so the cycle pays the per-asset round-trip only for the ones
     * its admission keeps (capability `gallery-status`).
     */
    val candidates: List<Candidate>,
    /**
     * Whether this walk read the **library itself** and returned every asset inside the policy's capture
     * window — which is what makes an in-window asset's absence from [candidates] evidence that it left the
     * library (capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk").
     *
     * False for a partial grant's selection snapshot (a de-selected photo is not a deleted one, and an
     * uploaded, later-deselected photo keeps its row — capability `limited-photo-access`) and for a library
     * the platform could not read (no candidates, and no evidence of anything). A walk that is not
     * authoritative deletes nothing.
     */
    val fullEnumeration: Boolean,
)

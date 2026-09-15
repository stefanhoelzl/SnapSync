package app.snapsync.ports

import app.snapsync.model.Candidate
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy

/**
 * What the upload cycle reads from the photo library: the change-token walk and the id-scoped resolve of
 * ledger keys (capability `ios-url-session-upload`, "Ledger keys resolve to uploadable resources").
 *
 * Its own port rather than two members of [BackgroundTransfer], because neither read is a transport concern:
 * on every tier both are the same PhotoKit fetches, and only the transfer lifecycle differs. Each composition
 * root binds it **once**, and no transport implements or forwards it. The partial-grant read discipline wraps
 * this port, not the transport (`SelectionScopedDiscovery`, capability `limited-photo-access`).
 *
 * Not [CandidateSource]: that read answers the admitted set for a count and carries no cursor, and a change
 * feed cannot honestly supply a count of the current set.
 */
interface UploadDiscovery {

    /**
     * Enumerate the asset resources changed since [sinceToken] (null / expired → a full enumeration),
     * returning them plus the cursor to persist once the cycle fully drains.
     *
     * [policy] carries the membership's capture-date cutoff (capability `photo-selection-policy`). A full
     * enumeration SHALL be scoped by it — walking the whole library costs one synchronous platform round-trip
     * per asset. An implementation MAY return assets captured before the cutoff (the cycle filters), but MUST
     * NOT omit any at or after it. The incremental change-token walk is already bounded by the change feed and
     * ignores the cutoff; the cycle filters its output the same way.
     */
    suspend fun discover(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery

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
 * Discovered resources plus the opaque cursor to persist once the cycle fully drains.
 *
 * [removedAssetIds] are the asset identifiers reported removed by the change feed this cycle
 * (normalized `/`→`_` to match the key scheme), used to prune their ledger rows incrementally;
 * empty on a full enumeration (the change feed isn't consulted). [fullEnumeration] is true when
 * this discovery enumerated the whole library (no/expired token), so [resources] holds **every**
 * current resource key — the live key-set the cycle reconciles the ledger against.
 */
class Discovery(
    /**
     * The assets the platform returned — **candidates**, not yet admitted. Each carries cheap facts and
     * fetches its own resources on demand, so the cycle pays the per-asset round-trip only for the ones
     * its admission keeps (capability `gallery-status`).
     */
    val candidates: List<Candidate>,
    val nextToken: ByteArray,
    val removedAssetIds: List<String> = emptyList(),
    val fullEnumeration: Boolean = false,
)

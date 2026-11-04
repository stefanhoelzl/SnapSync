package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.LedgerState

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it) and the last recorded lifecycle [state]. An
 * uploaded resource is immutable, so a `COMPLETED` entry's mere existence is the proof of upload;
 * there is no content version, and the ledger keeps no timestamp.
 *
 * The row keeps **no attempt count, no event provenance and no absence mark** — `10.sqm` dropped all three, because
 * nothing read them (decision record `changes/shrink-the-ledger-row`). The key is the bare, event-independent filename,
 * so a `COMPLETED` row stays valid across an event switch.
 *
 * The last four fields carry the **device manifest's presentation detail**: the asset's [creationDate] and, per
 * resource, its [role], [contentType] and human [originalFilename]. They make this table the single durable,
 * deletion-aware record of the device's in-event resources, so the manifest is a projection of it rather than a
 * parallel accumulator maintaining the same asset set with different columns.
 *
 * They default to `""` — the "not yet enriched" sentinel, where a row rests when the **join-time load** seeded
 * it from the device's stored-file listing, which carries no capture date. It is swept by the single writer's
 * next full enumeration ([LedgerStore.backfillManifestDetail]).
 */
class LedgerEntry(
    val key: String,
    val assetId: AssetId,
    val state: LedgerState,
    val creationDate: String = "",
    val role: ResourceRole = ResourceRole.PRIMARY,
    val contentType: String = "",
    val originalFilename: String = "",
    /**
     * The destination this row's upload was addressed to, or `null` for a row recorded before the
     * ledger kept it.
     *
     * It exists so a returned platform upload job can be resolved back to its row from **what the
     * external system persisted** (`docs/architecture.md`, "State and authority"). The OS-driven tier
     * hands PhotoKit a destination and the process dies; when the job comes back its `resource` is nil
     * and the destination is all that is left. Under the v1 byte route the key happened to be that
     * destination's last path segment — an accident of formatting that a route naming identity in its
     * path does not preserve.
     *
     * The PATH, not the whole URL: it is what the platform must keep in order to perform the request at
     * all, and it is unaffected by any handling of the query. In practice the two spellings coincide,
     * because a canonical [AssetId] and a role token contain only unreserved characters.
     */
    val destinationPath: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        creationDate == other.creationDate && role == other.role &&
        contentType == other.contentType && originalFilename == other.originalFilename &&
        destinationPath == other.destinationPath

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state)"
}

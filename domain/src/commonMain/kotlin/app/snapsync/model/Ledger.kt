package app.snapsync.model

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it), the last recorded lifecycle [state], the
 * [attempt] it belongs to, and the [eventId] that was joined when the row was recorded. An
 * uploaded resource is immutable, so a `COMPLETED` entry's mere existence is the proof of upload;
 * there is no content version, and the ledger keeps no timestamp.
 *
 * [eventId] is **provenance, not dedup state**: the key stays the bare event-independent filename,
 * no read consults [eventId], and a `COMPLETED` row stays valid across an event switch (spec
 * `sync-ledger`, "Event-independent key"). `""` is the pre-provenance sentinel — a row recorded
 * before the ledger carried the column (the 4.sqm migration default, or a staged-revert build's
 * writes) — which the single writer's per-cycle backfill sweeps to the then-live event id.
 *
 * The last four fields carry the **device manifest's presentation detail** (capability
 * `sync-ledger`): the asset's [creationDate] and, per resource, its [role], [contentType] and human
 * [originalFilename]. They make this table the single durable, deletion-aware record of the device's
 * in-event resources, so the manifest is a projection of it (capability `device-manifest`) rather
 * than a parallel accumulator maintaining the same asset set with different columns.
 *
 * They default to `""` — the "not yet enriched" sentinel, and a row can rest there two ways: it
 * predates the 5.sqm migration, or the **re-join reconcile** seeded it from the device's stored-file
 * listing, which returns filenames and therefore carries no capture date. Both are swept the same
 * way, by the single writer's next full enumeration ([LedgerStore.backfillManifestDetail]).
 */
class LedgerEntry(
    val key: String,
    val assetId: String,
    val state: LedgerState,
    val attempt: Int,
    val eventId: String,
    val creationDate: String = "",
    val role: ResourceRole? = null,
    val contentType: String = "",
    val originalFilename: String = "",
    /**
     * Whether the asset has left this device's library.
     *
     * The row is **kept** when that happens, because what it records — these bytes are on the backend —
     * is still true: nothing on the device deletes an uploaded object (capability `scheduled-cleanup`
     * owns the only deletion, and it deletes whole events). Keeping it is also what stops a restored
     * asset re-uploading, and iOS keeps a deleted photo recoverable for 30 days — the same order as an
     * event's whole life.
     *
     * It replaces a `DELETE`. Pruning conflated the deletion signal with "the policy stopped admitting
     * this", and because the prune was fed the policy-admitted set, raising a capture cutoff discarded
     * the `COMPLETED` rows of photos still in the library and still uploaded — making the narrowing
     * irreversible, since those rows are exactly what suppresses re-upload.
     */
    val absent: Boolean = false,
) {
    /** Whether this row still needs the manifest-detail backfill. */
    val needsManifestDetail: Boolean get() = creationDate.isEmpty()

    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        attempt == other.attempt && eventId == other.eventId &&
        creationDate == other.creationDate && role == other.role &&
        contentType == other.contentType && originalFilename == other.originalFilename &&
        absent == other.absent

    /** The same row, recorded as having left the library. Pure: nothing else about the row changes. */
    fun markedAbsent(): LedgerEntry = LedgerEntry(
        key = key,
        assetId = assetId,
        state = state,
        attempt = attempt,
        eventId = eventId,
        creationDate = creationDate,
        role = role,
        contentType = contentType,
        originalFilename = originalFilename,
        absent = true,
    )

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state, attempt=$attempt, eventId=$eventId)"
}

/**
 * Record one resource as a ledger row, carrying the **device manifest's** presentation detail
 * (capability `sync-ledger`) off the resource that caused the transition.
 *
 * The one place that mapping is made, so the manifest cannot disagree with the ledger about what a
 * resource is called or when it was taken. [role] is derived from the upload key rather than stored
 * twice; an unrecognized key yields `null`, which the projection treats as a row it cannot name.
 */
fun Resource.toLedgerRow(state: LedgerState, attempt: Int, eventId: String): LedgerEntry = LedgerEntry(
    key = filename,
    assetId = assetId,
    state = state,
    attempt = attempt,
    eventId = eventId,
    creationDate = metadata[RESOURCE_META_CREATION_DATE] ?: "",
    role = roleFromUploadKey(filename),
    contentType = metadata[RESOURCE_META_MIME] ?: contentType,
    originalFilename = metadata[RESOURCE_META_ORIGINAL_FILENAME] ?: "",
)

enum class LedgerState {
    /** Work was answered for this key — a hope; the engine cannot prove it was executed. */
    REQUESTED,

    /** The platform observed and reported a successful upload — a fact about the world. */
    COMPLETED,

    /** The platform reported a failed attempt; a retry was answered alongside. */
    FAILED,
}

/**
 * The ledger's lifetime truth in one snapshot-consistent read, counted by **photo (assetId), not
 * resource row**: [pending] = photos with any non-`COMPLETED` resource, [completed] = photos whose
 * resources are all `COMPLETED`.
 */
class LedgerAggregates(
    val pending: Int,
    val completed: Int,
) {
    override fun equals(other: Any?): Boolean = other is LedgerAggregates &&
        pending == other.pending && completed == other.completed

    override fun hashCode(): Int = 31 * pending + completed

    override fun toString(): String =
        "LedgerAggregates(pending=$pending, completed=$completed)"
}

/**
 * One outstanding resource: the [assetId] (photo) a non-`COMPLETED` [key] belongs to. The backlog
 * read returns these so a status projection can group outstanding resources by photo; the backend
 * never interprets them (it just reports the rows whose state is not `COMPLETED`).
 */
class PendingResource(val assetId: String, val key: String) {
    override fun equals(other: Any?): Boolean =
        other is PendingResource && assetId == other.assetId && key == other.key

    override fun hashCode(): Int = 31 * assetId.hashCode() + key.hashCode()

    override fun toString(): String = "PendingResource(assetId=$assetId, key=$key)"
}

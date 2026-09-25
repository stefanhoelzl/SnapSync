package app.snapsync.model

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it) and the last recorded lifecycle [state]. An
 * uploaded resource is immutable, so a `COMPLETED` entry's mere existence is the proof of upload;
 * there is no content version, and the ledger keeps no timestamp.
 *
 * The row keeps **no attempt count, no event provenance and no absence mark** — `10.sqm` dropped all three,
 * because nothing read them (decision record `changes/shrink-the-ledger-row`). The key is the bare,
 * event-independent filename, so a `COMPLETED` row stays valid across an event switch (spec `photo-sharing`,
 * "Event-independent key").
 *
 * The last four fields carry the **device manifest's presentation detail** (capability
 * `photo-sharing`): the asset's [creationDate] and, per resource, its [role], [contentType] and human
 * [originalFilename]. They make this table the single durable, deletion-aware record of the device's
 * in-event resources, so the manifest is a projection of it (capability `photo-sharing`) rather
 * than a parallel accumulator maintaining the same asset set with different columns.
 *
 * They default to `""` — the "not yet enriched" sentinel, and a row can rest there two ways: it
 * predates the 5.sqm migration, or the **join-time load** seeded it from the device's stored-file
 * listing, which carries no capture date. Both are swept the same
 * way, by the single writer's next full enumeration ([LedgerStore.backfillManifestDetail]).
 */
class LedgerEntry(
    val key: String,
    val assetId: String,
    val state: LedgerState,
    val creationDate: String = "",
    val role: ResourceRole? = null,
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
     * because a normalized `assetId` and a role token contain only unreserved characters.
     */
    val destinationPath: String? = null,
) {
    /** Whether this row still needs the manifest-detail backfill. */
    val needsManifestDetail: Boolean get() = creationDate.isEmpty()

    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        creationDate == other.creationDate && role == other.role &&
        contentType == other.contentType && originalFilename == other.originalFilename &&
        destinationPath == other.destinationPath

    /**
     * The same row in [state], every other field unchanged.
     */
    fun withState(state: LedgerState): LedgerEntry = LedgerEntry(
        key = key,
        assetId = assetId,
        state = state,
        creationDate = creationDate,
        role = role,
        contentType = contentType,
        originalFilename = originalFilename,
        destinationPath = destinationPath,
    )

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state)"
}

/**
 * Whether replacing the row [before] with [after] (either `null` for an insert or a delete) changes what the
 * device manifest projects from it — so whether the ledger's **manifest version** advances (capability
 * `photo-sharing`, "The manifest version orders the device's manifest snapshots").
 *
 * The SQLite store decides this in its triggers; this is the same rule for a store that has none (the
 * in-memory stores), stated once so no fake grows its own reading of it. `state` and `destinationPath` are
 * deliberately not compared: the manifest carries no upload state, and a bump per finished upload would
 * force a republish per cycle.
 */
fun changesManifestProjection(before: LedgerEntry?, after: LedgerEntry?): Boolean {
    if (before == null || after == null) return before != after
    return before.key != after.key ||
        before.assetId != after.assetId ||
        before.creationDate != after.creationDate ||
        before.role != after.role ||
        before.contentType != after.contentType ||
        before.originalFilename != after.originalFilename
}

/**
 * Record one resource as a ledger row, carrying the **device manifest's** presentation detail
 * (capability `photo-sharing`) off the resource that caused the transition.
 *
 * The one place that mapping is made, so the manifest cannot disagree with the ledger about what a
 * resource is called or when it was taken. [role] is derived from the upload key rather than stored
 * twice; an unrecognized key yields `null`, which the projection treats as a row it cannot name.
 */
fun Resource.toLedgerRow(
    state: LedgerState,
    destinationPath: String? = null,
): LedgerEntry = LedgerEntry(
    key = filename,
    assetId = assetId,
    state = state,
    creationDate = metadata[RESOURCE_META_CREATION_DATE] ?: "",
    role = roleFromUploadKey(filename),
    contentType = metadata[RESOURCE_META_MIME] ?: contentType,
    originalFilename = metadata[RESOURCE_META_ORIGINAL_FILENAME] ?: "",
    destinationPath = destinationPath,
)

enum class LedgerState {
    /**
     * This resource **needs an upload job**: the discovery walk found it, the membership's policy admitted it,
     * and nothing is in flight for it — either nothing has been attempted yet, or an attempt failed and
     * returned the row here. Those were once two states (`DISCOVERED`, `FAILED`); they were one fact to a
     * producer, and `10.sqm` rewrote the second into the first (decision record
     * `changes/shrink-the-ledger-row`, D3).
     *
     * The only state named for the **walk** rather than for an upload attempt, and the reason the ledger
     * can be the cycle's source of work at all: without it, the sole record of "this needs uploading"
     * lives in the walk's return value and dies with the cycle, so a cycle that could not enqueue
     * everything it saw had to re-walk the whole library next time to find the remainder. It is also what
     * lets the walk skip an asset it has already recorded (capability `photo-sharing`, "A walk re-reads only
     * the assets the ledger does not fully know"): the work lives in this row, not in a re-read.
     *
     * It is recorded **before** the first `createJob` of a cycle. It does not mean a job exists — that is
     * [REQUESTED], and the write-after-act invariant keeping those distinct is what lets two uploaders share one
     * ledger: a cycle picks only this state, so a row another cycle already has in flight is never re-picked
     * (decision record `changes/both-uploaders-active`).
     *
     * Not a done state ([isDone]) and **does** need a job ([needsJob]), so it counts toward the backlog
     * everywhere. It is nonetheless DECLARED in the device manifest: that document states what this device
     * intends to provide, and a resource the walk found and the policy admitted is exactly that
     * (capability `photo-sharing`). The backend tells "not yet" from "never" by comparing the declared
     * roles against the resources it has recorded — which is why declaring before the bytes land is the
     * point rather than a leak.
     *
     * Decision record: `changes/fix-cap-truncation-loop` (D1, D3, D4).
     */
    DISCOVERED,

    /** Work was answered for this key — a hope; the engine cannot prove it was executed. */
    REQUESTED,

    /**
     * The platform observed and reported a successful upload — a fact about the world, and a settled one:
     * nothing further is owed for the key.
     *
     * Written by whichever party the platform tells that the upload terminated, **at the moment it is
     * told** ([TerminalOutcome], through the ledger's guarded terminal write), by the join-time load for a
     * resource the device listing already holds, and by the `8.sqm` migration for rows an earlier build
     * left `UPLOADED`. Nothing a completion used to trigger remains: the device manifest declared the
     * resource at discovery, and the event-album placement happened when its upload was first enqueued.
     *
     * Decision record: `changes/retire-uploaded-state` (D1), superseding the `UPLOADED` state of
     * `changes/archive/2026-08-26-fix-lost-upload-acks`.
     */
    COMPLETED,
}

/**
 * How an upload **terminated**, as the platform reported it, and the state the ledger's guarded terminal write
 * records for each (capability `photo-sharing`): a success is [LedgerState.COMPLETED]; a failure returns the row
 * to [LedgerState.DISCOVERED], so the ledger's work read offers it again.
 *
 * A type rather than a [LedgerState] because that write is the one record operation reachable outside the
 * single writer's type-level protection: the party the platform tells is a callback holding only the key.
 * Fixing the recordable set in the parameter's type makes claiming a job exists — recording `REQUESTED` —
 * through it a compile error instead of a convention. The cases name what the **platform** reported: `FAILED`
 * is still what happened, although the ledger no longer has a state of that name.
 *
 * Decision records: `changes/retire-uploaded-state` (D6), `changes/shrink-the-ledger-row` (D3).
 */
enum class TerminalOutcome(val state: LedgerState) {
    COMPLETED(LedgerState.COMPLETED),
    FAILED(LedgerState.DISCOVERED),
}

/**
 * Whether a row in this state is **settled** — nothing further is owed for its key.
 *
 * The single decision behind every state-scoped ledger read. The backlog read, the aggregate counts and
 * the device-manifest projection all take [DONE_STATES] as a bound parameter rather than comparing `state`
 * to a literal, so adding a fourth state cannot land silently on one side of a query: this `when` has no
 * `else` and stops compiling until the new value is classified.
 *
 * That is not hypothetical caution. Three `.sq` predicates used to read `state != 'COMPLETED'` / `state =
 * 'COMPLETED'`, and while the Kotlin readers fail loudly on a new enum value (`SyncEngine`'s `when` has no
 * `else` either), those three would simply have filed a new state on one side of a string comparison with
 * no error anywhere.
 */
val LedgerState.isDone: Boolean
    get() = when (this) {
        LedgerState.COMPLETED -> true
        LedgerState.DISCOVERED, LedgerState.REQUESTED -> false
    }

/** The settled states, bound into every state-scoped storage read. See [isDone]. */
val DONE_STATES: List<LedgerState> = LedgerState.entries.filter { it.isDone }

/**
 * Whether a row in this state **needs an upload job** — nothing is in flight for its key, and its bytes
 * are not on the backend.
 *
 * The second, independent classification alongside [isDone], and the one that makes the ledger the
 * cycle's source of work: a producer asks for these rows rather than asking the library. The two axes do
 * not imply each other — [LedgerState.REQUESTED] is neither done nor in need of a job — so every state is
 * classified on both, and this `when` has no `else` for the same reason [isDone] has none.
 *
 * Only [LedgerState.DISCOVERED] needs one. A failed upload returns its row there, so the never-retried
 * failure and the never-enqueued remainder are one fact found by one read — which is why the separate
 * `FAILED` state could be retired (decision record `changes/shrink-the-ledger-row`).
 */
val LedgerState.needsJob: Boolean
    get() = when (this) {
        LedgerState.DISCOVERED -> true
        LedgerState.REQUESTED, LedgerState.COMPLETED -> false
    }

/** The states needing an upload job, bound into the work-source read. See [needsJob]. */
val NEEDS_JOB_STATES: List<LedgerState> = LedgerState.entries.filter { it.needsJob }

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

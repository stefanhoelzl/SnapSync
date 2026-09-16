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
    /**
     * The destination this row's upload was addressed to, or `null` for a row recorded before the
     * ledger kept it.
     *
     * It exists so a returned platform upload job can be resolved back to its row from **what the
     * external system persisted** (`module-architecture`, "State and authority"). The OS-driven tier
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
        attempt == other.attempt && eventId == other.eventId &&
        creationDate == other.creationDate && role == other.role &&
        contentType == other.contentType && originalFilename == other.originalFilename &&
        absent == other.absent && destinationPath == other.destinationPath

    /** The same row, recorded as having left the library. Pure: nothing else about the row changes. */
    fun markedAbsent(): LedgerEntry = withAbsent(true)

    /** The same row, recorded as being in the library again. Pure: nothing else about the row changes. */
    fun markedPresent(): LedgerEntry = withAbsent(false)

    /**
     * The same row in [state], every other field unchanged — what a bulk state change such as
     * `LedgerStore.demoteRequested` does to each row it matches.
     */
    fun withState(state: LedgerState): LedgerEntry = rebuilt(state = state, absent = absent)

    private fun withAbsent(absent: Boolean): LedgerEntry = rebuilt(state = state, absent = absent)

    private fun rebuilt(state: LedgerState, absent: Boolean): LedgerEntry = LedgerEntry(
        key = key,
        assetId = assetId,
        state = state,
        attempt = attempt,
        eventId = eventId,
        creationDate = creationDate,
        role = role,
        contentType = contentType,
        originalFilename = originalFilename,
        absent = absent,
        destinationPath = destinationPath,
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
fun Resource.toLedgerRow(
    state: LedgerState,
    attempt: Int,
    eventId: String,
    destinationPath: String? = null,
): LedgerEntry = LedgerEntry(
    key = filename,
    assetId = assetId,
    state = state,
    attempt = attempt,
    eventId = eventId,
    creationDate = metadata[RESOURCE_META_CREATION_DATE] ?: "",
    role = roleFromUploadKey(filename),
    contentType = metadata[RESOURCE_META_MIME] ?: contentType,
    originalFilename = metadata[RESOURCE_META_ORIGINAL_FILENAME] ?: "",
    destinationPath = destinationPath,
)

enum class LedgerState {
    /**
     * The discovery walk found this resource, the membership's policy admitted it, and nothing has been
     * attempted for it.
     *
     * The only state named for the **walk** rather than for an upload attempt, and the reason the ledger
     * can be the cycle's source of work at all: without it, the sole record of "this needs uploading"
     * lives in the walk's return value and dies with the cycle, so a cycle that could not enqueue
     * everything it saw had to re-walk the whole library next time to find the remainder — and could not
     * advance the change-token cursor, because advancing past a resource nothing records loses it
     * silently and permanently.
     *
     * It is recorded **before** the first `createJob` of a cycle, and it is what licenses that cycle's
     * cursor advance (capability `ios-photokit-upload`, "In-extension discovery via persistent change
     * token"). It does not mean a job exists — that is [REQUESTED], and the write-after-act invariant
     * keeping those distinct is what lets the stranded pass treat a `REQUESTED` row with no live task as
     * a lost transfer.
     *
     * Not a done state ([isDone]) and **does** need a job ([needsJob]), so it counts toward the backlog
     * everywhere. It is nonetheless DECLARED in the device manifest: that document states what this device
     * intends to provide, and a resource the walk found and the policy admitted is exactly that
     * (capability `device-manifest`). The backend tells "not yet" from "never" by comparing the declared
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
     * told** ([TerminalOutcome], through the ledger's guarded terminal write), by the re-join seed for a
     * resource the device listing already holds, and by the `8.sqm` migration for rows an earlier build
     * left `UPLOADED`. Nothing a completion used to trigger remains: the device manifest declared the
     * resource at discovery, and the event-album placement happened when its upload was first enqueued.
     *
     * Decision record: `changes/retire-uploaded-state` (D1), superseding the `UPLOADED` state of
     * `changes/archive/2026-08-26-fix-lost-upload-acks`.
     */
    COMPLETED,

    /** The platform reported a failed attempt; a retry was answered alongside. */
    FAILED,
}

/**
 * How an upload **terminated** — the only states a platform callback may record through the ledger's guarded
 * terminal write (capability `sync-ledger`).
 *
 * A type rather than a [LedgerState] because that write is the one record operation reachable outside the
 * single writer's type-level protection: the party the platform tells is a callback holding only the key.
 * Fixing the recordable set in the parameter's type makes recording `DISCOVERED` or `REQUESTED` through it a
 * compile error instead of a convention.
 *
 * Decision record: `changes/retire-uploaded-state` (D6).
 */
enum class TerminalOutcome(val state: LedgerState) {
    COMPLETED(LedgerState.COMPLETED),
    FAILED(LedgerState.FAILED),
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
        LedgerState.DISCOVERED, LedgerState.REQUESTED, LedgerState.FAILED -> false
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
 * [LedgerState.DISCOVERED] and [LedgerState.FAILED] are the same fact to a producer, differing only in
 * whether an attempt was already made. Collapsing them here is what makes the never-retried `FAILED` row
 * and the never-enqueued remainder one defect with one fix: before this, both were reachable only by a
 * walk that re-derived their resource, which an incremental walk does not do for an asset that has not
 * changed.
 */
val LedgerState.needsJob: Boolean
    get() = when (this) {
        LedgerState.DISCOVERED, LedgerState.FAILED -> true
        LedgerState.REQUESTED, LedgerState.COMPLETED -> false
    }

/** The states needing an upload job, bound into the work-source read. See [needsJob]. */
val NEEDS_JOB_STATES: List<LedgerState> = LedgerState.entries.filter { it.needsJob }

/**
 * Whether a row in this state records a **belief that the bytes are on the backend** — the ledger's own
 * claim, which is exactly what a check against the backend's listing must compare (capability
 * `upload-state-reconciliation`).
 *
 * The third classification alongside [isDone] and [needsJob], and independent of both: the two axes above
 * answer "is anything still owed?" and "should a job be made?", neither of which is the same question as
 * "does this row assert that the upload landed?".
 *
 * Today it classifies every state exactly as [isDone] does, and it stays a separate decision on purpose: a
 * comparison against the backend must not depend on what "settled" means, and a future state may separate
 * the two. The belief matters most on the OS-driven tier, where the returned upload job carries no HTTP
 * status (`PHAssetResourceUploadJob` has no `statusCode`), so a [LedgerState.COMPLETED] recorded there
 * cannot tell a stored `201` from a `502`.
 *
 * Exhaustive with no `else`, for the reason [isDone] has none: a state added without classifying it must
 * stop the compile rather than land silently on one side of a comparison that decides whether a lost
 * photo is ever noticed.
 *
 * Decision record: `changes/retire-uploaded-state` (D5).
 */
val LedgerState.bytesBelievedStored: Boolean
    get() = when (this) {
        LedgerState.COMPLETED -> true
        LedgerState.DISCOVERED, LedgerState.REQUESTED, LedgerState.FAILED -> false
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

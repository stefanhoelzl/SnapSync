package app.snapsync.domain.upload

enum class LedgerState {
    /**
     * This resource **needs an upload job**: the discovery walk found it, the membership's policy admitted it,
     * and nothing is in flight for it — either nothing has been attempted yet, or an attempt failed and
     * returned the row here. Those were once two states (`DISCOVERED`, `FAILED`); they were one fact to a
     * producer, and `10.sqm` rewrote the second into the first (decision record
     * `changes/shrink-the-ledger-row`, D3).
     *
     * The only state named for the **walk** rather than for an upload attempt, and the reason the ledger can be the
     * cycle's source of work at all: without it, the sole record of "this needs uploading" lives in the walk's return
     * value and dies with the cycle, so a cycle that could not enqueue everything it saw had to re-walk the whole
     * library next time to find the remainder. It is also what lets the walk skip an asset it has already recorded (a
     * walk re-reads only the assets the ledger does not fully know): the work lives in this row, not in a re-read.
     *
     * It is recorded **before** the first `createJob` of a cycle. It does not mean a job exists — that is
     * [REQUESTED], and the write-after-act invariant keeping those distinct is what lets two uploaders share one
     * ledger: a cycle picks only this state, so a row another cycle already has in flight is never re-picked
     * (decision record `changes/both-uploaders-active`).
     *
     * Not a done state ([isDone]) and **does** need a job ([needsJob]), so it counts toward the backlog everywhere. It
     * is nonetheless DECLARED in the device manifest: that document states what this device intends to provide, and a
     * resource the walk found and the policy admitted is exactly that. The backend tells "not yet" from "never" by
     * comparing the declared roles against the resources it has recorded — which is why declaring before the bytes land
     * is the point rather than a leak.
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
     * told** ([TerminalOutcome], through the ledger's guarded terminal write), and by the join-time load for a
     * resource the device listing already holds. Nothing a completion used to trigger remains: the device manifest
     * declared the resource at discovery, and the event-album placement happened when its upload was first enqueued.
     *
     * Decision record: `changes/retire-uploaded-state` (D1), superseding the `UPLOADED` state of
     * `changes/archive/2026-08-26-fix-lost-upload-acks`.
     */
    COMPLETED,
}

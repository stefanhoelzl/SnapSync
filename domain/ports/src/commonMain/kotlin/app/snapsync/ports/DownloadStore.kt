package app.snapsync.ports

import app.snapsync.model.AssetRef
import app.snapsync.model.DownloadCounts
import app.snapsync.model.ImportableAsset
import app.snapsync.model.PendingDownload
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.StagedResource
import app.snapsync.model.UnconfirmedImport

/**
 * The read-only suppression projection the **upload extension** consumes: the set of local
 * `createdLocalId`s of foreign assets this device has downloaded+imported. Discovery drops these so a
 * downloaded asset is never re-uploaded (the echo). Kept as its own narrow interface so the extension
 * depends on the read, not on the full app-side [DownloadStore] surface.
 */
interface SuppressionSource {
    suspend fun suppressedLocalIds(): Set<String>
}

/**
 * The app-written download store (capability `receiving-photos`). Records foreign assets selected for
 * download, their per-resource staging, and the import outcome (`createdLocalId`). Idempotency and
 * cross-event dedup are by [AssetRef]; terminal (`IMPORTED`) rows are permanent.
 */
interface DownloadStore : SuppressionSource {
    /**
     * True if this foreign asset is **settled** — imported, or settled as permanently unimportable — so
     * discovery neither re-plans nor re-downloads it. Both terminal states answer yes: re-planning an
     * unimportable ref would recreate the resource rows that settling it dropped, and nothing about it can
     * succeed.
     */
    suspend fun isSettled(ref: AssetRef): Boolean

    /**
     * The members of [refs] that are **settled** — [isSettled] answered for a whole union in **one read**
     * (capability `receiving-photos`). A ref with no row, or a non-terminal row, is absent from the answer.
     *
     * Exists because a reconcile asks this for every foreign asset of the union, and asking it one ref at
     * a time is one store round-trip per asset, repeated on every trigger while a backlog exists.
     * Event-blind like [importedLocalIds]: the caller passes the refs it is about to plan.
     */
    suspend fun settledAmong(refs: Collection<AssetRef>): Set<AssetRef>

    /**
     * The created local identifier of each of [refs] whose row is [DownloadState.IMPORTED] (capability
     * `receiving-photos`). A ref with no row, a non-terminal row — including an **unconfirmed** one that
     * carries a marker, whose asset has not been adjudicated yet — or an [DownloadState.UNIMPORTABLE] row is
     * absent from the answer.
     *
     * **Event-blind, like the rest of this store.** It does not know which event a download was for, and
     * must not start to: one row serves every event that lists its ref. The caller decides which refs
     * belong to an event by passing the refs from that event's union — the event album's gather
     * (capability `event-album`) is the one consumer. Deliberately not on [SuppressionSource]: the
     * extension never gathers.
     */
    suspend fun importedLocalIds(refs: Collection<AssetRef>): Map<AssetRef, String>

    /** Record a foreign asset (with its capture [creationDate]) and its expected resources as PENDING (idempotent; never downgrades IMPORTED). */
    suspend fun plan(ref: AssetRef, creationDate: String, resources: List<PlannedResource>)

    /**
     * [plan] every one of [assets], in **one transaction** (capability `receiving-photos`): each asset and its
     * resources are recorded exactly as [plan] records them — idempotent, never downgrading a terminal row,
     * refreshing only unstaged urls — and no reader can observe an asset without its resources.
     *
     * One transaction rather than one per asset because every store transaction is a durable commit: a
     * reconcile planning a backlog of a hundred assets paid a hundred of them, on every trigger, while the
     * backlog lasted (measured on an iPhone XS in a background wake: ~11.5 s for 101 assets).
     */
    suspend fun planAll(assets: List<PlannedAsset>)

    /** The not-yet-staged resources across all non-imported assets — the download work queue. */
    suspend fun pendingDownloads(): List<PendingDownload>

    /** Mark a resource's download as sent to the OS (a background transfer now exists) — the in-flight marker. */
    suspend fun markEnqueued(ref: AssetRef, resourceKey: String)

    /**
     * [markEnqueued] every one of [downloads], in **one transaction** — the batch a reconcile sends to the OS
     * is marked with one durable commit rather than one per resource.
     */
    suspend fun markAllEnqueued(downloads: Collection<PendingDownload>)

    /** Mark a resource's bytes downloaded and durably staged at [stagedPath]. */
    suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String)

    /**
     * Assets whose every expected resource is staged and that are not yet imported — ready to import.
     *
     * **Excludes rows carrying a `createdLocalId`**: those already have an asset in the library, and
     * importing them again is the duplicate this capability exists to prevent. They leave through
     * [unconfirmedImports] to be adjudicated, and re-enter here only once their marker is cleared.
     */
    suspend fun importableAssets(): List<ImportableAsset>

    /** Rows whose asset was created but whose import was never confirmed — to be adjudicated, not re-imported. */
    suspend fun unconfirmedImports(): List<UnconfirmedImport>

    /** The staged resources of an asset, to feed one PHAssetCreationRequest. */
    suspend fun stagedResources(ref: AssetRef): List<StagedResource>

    /** Mark an asset imported and record the created local identifier (the suppression handle). */
    suspend fun markImported(ref: AssetRef, createdLocalId: String)

    /**
     * Record ONLY the created local identifier, leaving the row non-terminal — the marker written from
     * **inside** the platform's change block, before the created asset is observable, so the upload echo
     * is closed even if the confirmation never arrives (capability `receiving-photos`).
     *
     * **Not `suspend`, alone on this interface**, and not by preference: iOS's `performChanges` change
     * block cannot call a suspending function, and this write has to happen inside it or the asset is
     * observable before it is suppressed. The platform constraint is the whole reason the method exists,
     * so it shapes the signature.
     *
     * The pair *(non-terminal state, non-null `createdLocalId`)* is the **unconfirmed** row: an asset was
     * created for this ref and its import was never confirmed. Because the block always completes before
     * the library commits, a created asset always has a marker — so this is a record of an irreversible
     * act, and [pruneNonTerminal] must not delete a row that carries one.
     *
     * **Unguarded, alone among the three, and deliberately.** It *creates* the addressing the other two
     * match; there is nothing for it to match against. The states that would make a guard meaningful are
     * unreachable: a terminal row is excluded from importable work and no second import for a ref can run
     * concurrently, and a row carrying an older marker is not importable until adjudication clears that
     * marker — which is itself the decision that no asset exists for it. A clause here would protect
     * against neither and would read as load-bearing to the next person.
     *
     * **Returns whether it updated a row, and `false` is an emergency.** The only way to match nothing is
     * for the row to have been DELETED between this import being selected and its change block running —
     * the failure [pruneNonTerminal]'s `protecting` set exists to prevent. Its consequence is an asset
     * created with no suppression handle, which this device then uploads back into someone else's event
     * days later with nothing anywhere recording why. The caller SHALL report that loudly: it is the only
     * evidence that the protection failed, and without it a safety gate's failure is visible solely through
     * its damage.
     *
     * Idempotent; a marker written for a change that then fails is cleared by [clearCreatedLocalId].
     */
    fun recordCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean

    /**
     * Undo [recordCreatedLocalId] for a change the platform reported as **failed** — the exact mirror,
     * from the same completion callback — and the write an *absent* verdict is applied through.
     *
     * A marker is cleared for exactly one reason: the library said its change failed, or said the asset
     * it names does not exist. Never because time passed, because nothing is awaiting the transaction any
     * longer, or because a lookup answered *absent* while that transaction was still open — it may still
     * commit, and clearing the marker is what orphans the created asset (capability `receiving-photos`).
     *
     * **Guarded on [createdLocalId] AND on the row still being non-terminal**, and the guard is in the
     * store's write rather than in a caller's preceding `if`, because two writers reach this with no shared
     * lock: the completion callback runs on the platform's own queue. A read-then-write pair is not atomic
     * against the writer that does not take the caller's lock, which is why the marker-scoped read this
     * replaces never closed the window it was introduced for. Both halves of the guard earn their place —
     * without the marker half a late failure clears a marker the row no longer holds, and without the state
     * half it strips the marker off a row adjudication has already settled as *present*, which is permanent
     * because a terminal row is never adjudicated or re-imported again.
     *
     * Returns whether it applied, so a caller can tell "the verdict landed" from "the row moved on" —
     * different answers, and the second must not be acted on further.
     */
    fun clearCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean

    /**
     * The **success** mirror of [recordCreatedLocalId]: settle the row against the marker it already
     * holds, from the platform's completion callback itself (capability `receiving-photos`).
     *
     * Written here rather than left to the caller because the completion is the party that LEARNS the
     * outcome, and it runs whether or not anything is still awaiting the transaction. An import whose
     * wait was abandoned on its deadline therefore settles itself, instead of staying unconfirmed until
     * some later pass pays for a synchronous, thread-blocking library lookup to discover what this
     * callback already knew.
     *
     * **Guarded on the marker.** A completion that arrives after the row's marker was cleared and
     * replaced SHALL NOT settle that row: it would mark it terminal against an identifier it no longer
     * describes, and the asset the row now points at would drop out of the suppression set. The guard
     * belongs in the store — in the `WHERE` clause, not in a caller's `if` — because two writers reach
     * this without a shared lock.
     *
     * Non-suspending, like its two siblings, because the platform's completion callback cannot call a
     * suspending function.
     *
     * Returns whether it applied — the same reason [clearCreatedLocalId] does. A *present* verdict that
     * settled nothing must not go on to release that row's staged bytes: those files belong to whatever
     * the row moved on to, and a live import is reading from them.
     */
    fun confirmCreatedLocalId(ref: AssetRef, createdLocalId: String): Boolean

    /**
     * Settle a row as permanently unimportable, reporting whether it applied (capability `receiving-photos`).
     *
     * Called when the library rejected a resource's content and consumed its staged file, so no bytes
     * remain to retry from and no asset was created. Guarded on the row still being non-terminal, because
     * the completion callback that reaches this takes no lock and a row adjudication already settled must
     * not be overwritten.
     *
     * The caller SHALL report the settlement at a severity that reaches the crash-reporting sink: a photo
     * that will never arrive is otherwise absent from the library with no error surface and absent from the
     * logs except as a repetition of the failure that caused it.
     */
    suspend fun settleUnimportable(ref: AssetRef): Boolean

    /**
     * The download projection's counts, in **one round-trip** (capability `receiving-photos`).
     *
     * One read rather than three, for the same reason the upload ledger's `aggregates()` is one: these counts
     * are published together as a single projection, and the status group requires each of its members to be
     * internally consistent (`sync-status`, "The cheap local status reads are one bounded group").
     *
     * Reading them separately made that consistency an ARGUMENT rather than a property — safe only because
     * `IMPORTED` is terminal and every imported row is also counted in the denominator, so a torn read could
     * only ever understate completeness. That argument holds today and nothing enforces it: a later change to
     * what the denominator excludes (as design D8 already did once) could break it silently, surfacing as a
     * wrong arrow with no test naming the cause. One read makes the question unaskable instead of answered.
     */
    suspend fun counts(): DownloadCounts

    /**
     * Drop non-terminal rows on leave/switch/reset, **returning the staged paths those rows owned** so the
     * caller frees exactly the files it just stranded. Imported rows — and any row carrying a marker — are
     * preserved regardless of [protecting].
     *
     * **One operation, in one transaction, deliberately.** Reading the paths and then pruning is two reads
     * at two instants over a store that writers who structurally CANNOT take the caller's lock mutate in
     * between — [recordCreatedLocalId] / [clearCreatedLocalId] / [confirmCreatedLocalId] are non-suspending
     * because PhotoKit's change and completion blocks cannot call a suspending function. A marker cleared in
     * that gap turns a row the read protected into a row the prune deletes, leaving its files on disk with
     * no row referencing them: nothing can ever find them again, and the leak survives relaunch.
     *
     * [protecting] is the refs whose imports are **in flight**. It is needed because an import can be
     * claimed BEFORE its change block runs, so its row legitimately carries no marker yet and no state-based
     * predicate can tell it from ordinary prunable work. Dropping one makes that change block's marker write
     * land on nothing, and the asset it creates is then uploaded back into the event.
     *
     * **Required, never defaulted**: a permissive default on a safety gate is how a caller ships without
     * one. An empty set is a claim that nothing is in flight, not an omission.
     */
    suspend fun pruneNonTerminal(protecting: Set<AssetRef>): List<String>

    // --- staged-byte lifetime (capability `receiving-photos`) ------------------------------------------
    //
    // The store records WHERE an asset's bytes are; releasing them is the download side's job. These
    // reads exist so it can, and each is scoped to rows whose bytes are provably no longer needed.

    /** Staged paths of assets whose import is CONFIRMED — redundant bytes, feeding the release pass. */
    suspend fun stagedPathsOfImportedAssets(): List<String>

    /**
     * Drop one asset's resource rows, once its bytes have been released — so the store never records a
     * staged path for a file that no longer exists, and so a release pass over confirmed assets is
     * **self-extinguishing** (the rows that made the work findable are gone). Safe because nothing reads
     * an imported row's resources.
     */
    suspend fun dropResources(ref: AssetRef)

    /**
     * Drop the resource rows of **every** confirmed asset — the bulk half of the staged-byte reclaim.
     * Paired with [stagedPathsOfImportedAssets] this makes the reclaim self-extinguishing: the rows that
     * made the work findable are gone, so a second pass finds nothing.
     */
    suspend fun dropResourcesOfImportedAssets()
}

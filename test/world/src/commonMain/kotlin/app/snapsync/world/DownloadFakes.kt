package app.snapsync.world

import app.snapsync.model.StartResult
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.fake.LibraryChangeAnswers
import kotlinx.coroutines.CompletableDeferred
import app.snapsync.model.TransferOutcome
import app.snapsync.model.AssetRef

/**
 * The operator-driven download **edge** (`docs/testing.md`): a fake [Download] the world composes the **real**
 * [app.snapsync.services.downloads.DownloadJobs] over — so the bounded window, the tag codec, the URL guard
 * and the integrity judgement all run against it, and only the platform is played.
 *
 * [finish] mirrors the real `URLSession` delegate, including the ordering the integrity check depends on: the finish
 * callback hands the facts and a temporary file, and the completion follows it whether or not anything went wrong —
 * which is what frees the window slot.
 */
class FakeDownload(
    /**
     * The operating system's session: the transfers it holds for this app. Durable across a relaunch — a relaunched
     * process finds the transfers the dead one started, and their completions arrive there, as a background
     * `URLSession`'s do.
     */
    val started: MutableList<Started> = mutableListOf(),
    /**
     * Where the OS leaves a finished transfer's bytes: given the transfer's description, write its temporary file and
     * answer the platform path handed to the finish handler — as a `URLSession` leaves a temp file the app must move
     * before its callback returns. The default leaves none: a path nothing can adopt.
     */
    private val leaveTempFile: (description: String) -> String = { "temp:/$it" },
) : Download {

    /** Inspection: a transfer started through this session. */
    class Started(val url: String, val description: String) {
        var cancelled: Boolean = false
    }

    private var handlers: DownloadHandlers? = null

    /**
     * Whether this launch's process has brought the session up — by starting, cancelling, or being handed the
     * session's events. A relaunch ([relaunched]) is a new process that has brought up nothing.
     */
    var realized: Boolean = false
        private set

    override fun listen(handlers: DownloadHandlers) {
        this.handlers = handlers
    }

    /** `NotStarted` for a URL that is not one, as the real session answers — never a throw (`DownloadContract`). */
    override fun start(url: String, tag: String): StartResult {
        realized = true
        if (url.isBlank()) return StartResult.NotStarted
        started += Started(url, tag)
        return StartResult.Started
    }

    /** Cancels every transfer the session holds — ones a dead process started included — each completing with an error. */
    override suspend fun cancelAll() {
        realized = true
        started.filterNot { it.cancelled }.forEach {
            it.cancelled = true
            registered().onCompleted(it.description, "cancelled")
        }
    }

    /** The transfers still awaiting a finish, de-duplicated by tag. */
    fun inFlight(): List<Started> = started.filterNot { it.cancelled }.distinctBy { it.description }

    /**
     * Deliver a finish for [description], exactly as the real delegate does: the facts and a temporary file, then the
     * completion. A rejected [outcome] leaves the resource un-staged — the world's pending-for-retry state.
     */
    fun finish(description: String, outcome: TransferOutcome = HEALTHY) {
        registered().onFinished(description, outcome, leaveTempFile(description))
        registered().onCompleted(description, null)
    }

    /** Operator lever: the operating system relaunches the app for this session's events, handing [completion]. */
    fun handBack(completion: Completion) {
        registered().onBackgroundEvents(completion)
        realized = true
    }

    /** Operator lever: the session reports every event delivered (`urlSessionDidFinishEvents`). */
    fun reportEventsDrained() = registered().onEventsDrained()

    /** The process died: the next one has brought up nothing yet (the transfers themselves survive). */
    fun relaunched() {
        realized = false
    }

    private fun registered(): DownloadHandlers =
        checkNotNull(handlers) { "no composition listened to the download session — nothing would receive this" }

    companion object {
        /** An ordinary healthy transfer: `200`, no declared length — what staging assumes by default. */
        val HEALTHY: TransferOutcome =
            TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
    }
}

/**
 * The world's import rigging, held by its [WorldGallery]: the import itself — the two-phase marker through the
 * registered handlers, the fresh identifier per creation, the asset landing in the gallery — is the honest
 * `:adapter:generic:fake` gallery's, the one `GalleryImportContract` holds to the PhotoKit adapter. What lives here
 * is the operator's script for how the library ANSWERS a change, supplied as the fake's [LibraryChangeAnswers],
 * plus the inspection a test reads.
 *
 * The levers ([failNextImport], [failNextImportAfterCreating], [suspendNextImport],
 * [suspendNextImportAfterCommit]) are what let a test reach each of the ways an import can end badly —
 * including the one that has no ending at all, where the transaction is held open while other triggers run.
 */
class WorldImports {

    /** Inspection: the source refs imported, one entry per created asset (so a repeat shows up twice). */
    val imported = mutableListOf<AssetRef>()

    /** Failure lever: the next import returns `Failed` **before** creating anything (cleared after one firing). */
    var failNextImport: Boolean = false

    /**
     * Failure lever: the next import writes its marker, creates the asset, and then reports `Failed` —
     * the real adapter's "commit reported failure after the block ran" path, where the mirror clears the
     * marker again. Cleared after one firing.
     */
    var failNextImportAfterCreating: Boolean = false

    /**
     * The lever the `SNAPSYNC-9` guard is about: the next import writes its marker — the change block ran
     * — and then **suspends before the commit lands**, holding the transaction open until the test
     * resolves it.
     *
     * Suspending, rather than returning a report about an abandonment, is the whole point. While it is
     * parked the gallery does not hold the asset, so the photo library answers *absent* about a
     * transaction that is still open — honest, and catastrophic to act on — and the ref is claimed. A
     * lever that merely *returned* that state let a test observe the aftermath; only this one lets a
     * second trigger run **while** the transaction is live, which is the interleaving the defect occurs
     * in and the one the download controller's claim exists to close.
     *
     * Nothing is settled while it is parked: no gallery asset, no clear (it may still land), no confirm.
     * Cleared after one firing.
     */
    var suspendNextImport: Boolean = false

    /**
     * The same hold, one step later: the marker is written, the commit **has** landed (the asset is in the
     * gallery), and only the report is missing — so a presence lookup answers *present* about it.
     *
     * Distinct from [suspendNextImport], where the library answers *absent* about a transaction that is
     * still open. Both leave an unconfirmed row and both stay claimed; only this one is recoverable by
     * adjudication, and it is the shape a process death leaves behind. Cleared after one firing.
     */
    var suspendNextImportAfterCommit: Boolean = false

    /** Signalled by [resumeSuspendedImport] to release a parked import with its chosen outcome. */
    private var parked: CompletableDeferred<Boolean>? = null

    /**
     * Completes once an import has actually parked, so a test can await the live transaction rather than
     * guessing at a delay — a race here would make every test built on this lever flaky.
     *
     * **Replaced on every park**, because a single completed deferred makes the lever single-shot in the
     * worst way: a second `await` would return the STALE ref immediately, the test would drive its
     * triggers before the second import had parked, and the resume would then find nothing suspended.
     */
    var suspendedImport: CompletableDeferred<AssetRef> = CompletableDeferred()
        private set

    /**
     * Deliver the parked import's outcome, driving the real completion path for it: on [succeeded] the
     * asset lands and the row is settled against the marker it holds; otherwise that marker is cleared.
     */
    fun resumeSuspendedImport(succeeded: Boolean) {
        val gate = parked ?: error("no import is suspended")
        parked = null
        gate.complete(succeeded)
    }

    /**
     * How many times ONE ref may be imported before this importer raises (capability
     * `docs/testing.md`).
     *
     * An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a HANG — which
     * names no defect and proves nothing. The cap converts it into an assertion failure that names the
     * count, so removing the drain's attempted-set produces a red test rather than a stuck one.
     */
    var attemptCap: Int = 50

    private val attempts = mutableMapOf<AssetRef, Int>()

    /** Parks until [resumeSuspendedImport]; answers the failure message when resumed as failed. */
    private suspend fun park(ref: AssetRef): String? {
        val gate = CompletableDeferred<Boolean>()
        parked = gate
        if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
        suspendedImport.complete(ref)
        return if (gate.await()) null else "suspended import resumed as failed"
    }

    /** The operator's script for how the library answers each change — the world gallery's honest fake reads it. */
    internal val answers = object : LibraryChangeAnswers {
        override suspend fun beforeChange(ref: AssetRef): String? {
            if (!failNextImport) return null
            failNextImport = false
            return "forced"
        }

        override suspend fun beforeCommit(ref: AssetRef): String? {
            if (!suspendNextImport) return null
            suspendNextImport = false
            return park(ref)
        }

        override suspend fun afterCommit(ref: AssetRef): String? {
            imported += ref
            if (suspendNextImportAfterCommit) {
                suspendNextImportAfterCommit = false
                park(ref)?.let { return it }
            }
            if (!failNextImportAfterCreating) return null
            failNextImportAfterCreating = false
            return "forced after creating"
        }
    }

    /** Counts an import of [ref] against [attemptCap], raising at the cap: a live-lock names itself instead of hanging. */
    internal fun attempt(ref: AssetRef) {
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        check(attempt <= attemptCap) {
            "imported ${ref.sourceAssetId} $attempt times (cap $attemptCap) — the drain is live-locking " +
                "on one ref instead of offering it once"
        }
    }
}



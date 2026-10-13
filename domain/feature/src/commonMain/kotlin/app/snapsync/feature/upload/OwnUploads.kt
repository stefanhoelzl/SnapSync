package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.services.gallery.GalleryAccessState

/**
 * The app's own uploads as a wake runs them (decision record `changes/own-work-per-wake`): the steps a wake's
 * sequences put in order, each answering what it did, and the rule that adds those answers up. The steps decide;
 * the sequences only order them.
 *
 * - [topUp]: job creation from the ledger — what a freed upload slot needs, and the first step of every wake, so the
 *   photos already known move before a walk the operating system may cut.
 * - [walk]: the library walk → manifest publish, under a full grant only. Under a partial grant the selection is the
 *   scope, and a selection change walks it as its own work.
 * - [topUpAfter]: the top-up the walk's new rows are due — only when it recorded any.
 */
class OwnUploads(
    private val cycle: UploadCycle,
    private val access: GalleryAccessState,
    /** The app's admission right now: whether a finished upload's freed slot may be filled. */
    private val admission: () -> UploadAdmission,
) {
    suspend fun topUp(): CycleResult = cycle.topUp()

    /** The walk → publish under a full grant; `null` — not walked — under any other. */
    suspend fun walk(): WalkOutcome? = if (access.full) cycle.walkAndPublish() else null

    /**
     * A selection change's own work under a partial grant: the walk → publish over the selection the member just
     * changed — whatever the grant, since the cycle's discovery resolves from the snapshot there.
     */
    suspend fun walkSelection(): WalkOutcome = cycle.walkAndPublish()

    /** The top-up after [walked], when it recorded rows; `null` when none were due. */
    suspend fun topUpAfter(walked: WalkOutcome?): CycleResult? = if (walked?.addedRows == true) cycle.topUp() else null

    /** Whether a finished upload's freed slot may be filled now — the app may create. */
    fun mayFillSlot(): Boolean = admission().admits

    /**
     * What one wake's uploads came to, over its steps' [results] in order (a step not taken is `null`). The latest
     * `SKIPPED` wins: the freshest gate answer says the membership contributes nothing. Then work remaining if any step
     * left some (a paused step's work remains too), then a failure, then completion — and a wake that ran no step,
     * completion.
     */
    fun outcome(vararg results: CycleResult?): CycleResult {
        val ran = results.filterNotNull()
        return when {
            ran.lastOrNull() == CycleResult.SKIPPED -> CycleResult.SKIPPED
            ran.any { it == CycleResult.PROCESSING || it is CycleResult.Paused } -> CycleResult.PROCESSING
            CycleResult.FAILED in ran -> CycleResult.FAILED
            else -> CycleResult.COMPLETED
        }
    }
}

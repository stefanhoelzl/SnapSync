package app.snapsync.compose

import app.snapsync.model.CandidateRead
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.CandidateSource
import kotlinx.coroutines.flow.StateFlow

/**
 * The one [CandidateSource] the app's consumers hold: it decides **where candidates come from** by the
 * current photo-access grant, so no consumer has to (capability `limited-photo-access`, *"the mode
 * difference is one source impl, not a branch in the policy or its consumers"*).
 *
 * That principle was already true of the policy and false of the consumers. The status total had two
 * entry points (`refresh` and `refreshFrom`) and the join preview had a `when (permission)` arm, so each
 * restated the grant distinction — and it is the restatement, not the reading, that lets two paths drift.
 *
 * - **`GRANTED`** → [walk], the platform's bounded library walk.
 * - **`LIMITED`** → the user's hand-picked [selection], which the admission then filters exactly as it
 *   would a walk. The snapshot arrives **already read, with resources**, from the sanctioned read points
 *   (the cold-launch baseline and the photo-selection-change observer). That eagerness is the mechanism
 *   keeping every library *fetch* in-flow: a deferred read would have to re-fetch by local identifier at
 *   upload time, and holding the resources means no later library read is needed at all. (Not an alert
 *   argument: iOS's limited-access alert is armed once per **out-of-scope library change** and merely
 *   surfaced by the next read, so read count does not move it — `limited-photo-access`. The reason that
 *   stands is that under a partial grant the selection *is* the scope, and this is fewer round-trips.)
 *   `candidatesFromResources` is therefore the honest adapter here — the resources genuinely are in hand.
 * - **`DENIED` / `NOT_DETERMINED`** → [CandidateRead.NotReadable]. Nothing is readable, which is a
 *   different answer from *nothing qualifies* — and it is this source's to give, not the consumer's to
 *   re-derive. Both consumers used to keep a grant check for exactly this, which is the restatement that
 *   lets two paths drift.
 *
 * ## The snapshot that has not arrived
 *
 * `LIMITED` has **two** states, and collapsing them is what made this source's own bug. Between the grant
 * turning partial and the first sanctioned read landing, [selection] is `null`: the app holds no selection
 * and may not go looking. That is *not* an empty selection — an empty selection is a counted zero that
 * legitimately settles the screen for a receive-only member, while an un-arrived one settles it for a
 * member who has photos selected and simply has not been told which yet. Because the status projection
 * publishes only `Ready` (capability `sync-status`), that frame cannot be retracted, and the honest count
 * that follows reads as the screen going backwards — `SNAPSYNC-14` / `SNAPSYNC-16`, one grant over from
 * where they were fixed.
 *
 * On a cold launch the race is not close: the baseline read is a PhotoKit fetch plus an eager per-asset
 * resource read (~110 ms each), while the foreground status refresh is two SQLite reads and an in-memory
 * count. The refresh wins, every time.
 *
 * The sibling collapse in `AppCore.selectionScope()` — the same cell, the same `?: emptyList()` — is
 * **kept**, and deliberately: a scoped discovery preserves its walk cursor and prunes nothing, so its
 * empty answer costs one idle cycle that the next emission re-runs. Its answer is retryable; a settled
 * screen is not.
 *
 * Seated in `compose/` because choosing between two ports by a third port's state is composition, and
 * because `AppPorts` is where both halves are already available.
 */
class PermissionAwareCandidateSource(
    private val permission: StateFlow<PermissionStatus>,
    private val walk: CandidateSource,
    private val selection: StateFlow<List<Resource>?>,
) : CandidateSource {

    override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
        when (permission.value) {
            PermissionStatus.GRANTED -> walk.candidates(policy)
            PermissionStatus.LIMITED -> selection.value
                ?.let { CandidateRead.Readable(candidatesFromResources(it)) }
                ?: CandidateRead.NotReadable
            PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED -> CandidateRead.NotReadable
        }
}

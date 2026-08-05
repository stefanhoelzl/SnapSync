package app.snapsync.compose

import app.snapsync.model.Candidate
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
 * - **`DENIED` / `NOT_DETERMINED`** → no candidates. Note this answers *"nothing is readable"*, not
 *   *"nothing qualifies"*; a consumer that must distinguish "no photos" from "no answer available" — the
 *   join preview renders no row rather than a zero — keeps its own grant check. That is a different
 *   question from the one this source answers.
 *
 * Seated in `compose/` because choosing between two ports by a third port's state is composition, and
 * because `AppPorts` is where both halves are already available.
 */
class PermissionAwareCandidateSource(
    private val permission: StateFlow<PermissionStatus>,
    private val walk: CandidateSource,
    private val selection: StateFlow<List<Resource>?>,
) : CandidateSource {

    override suspend fun candidates(policy: SelectionPolicy): List<Candidate> =
        when (permission.value) {
            PermissionStatus.GRANTED -> walk.candidates(policy)
            // A null snapshot is the honest state between a grant turning partial and the first observer
            // emission arriving: there is nothing selected that we know of, and we may not go looking.
            PermissionStatus.LIMITED -> candidatesFromResources(selection.value.orEmpty())
            PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED -> emptyList()
        }
}

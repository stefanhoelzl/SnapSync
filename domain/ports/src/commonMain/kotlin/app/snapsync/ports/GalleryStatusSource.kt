package app.snapsync.ports

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the own-device upload total `N`: a level-triggered state holder whose current
 * value is always available synchronously, so the status projection never has to guess while waiting
 * for a first read.
 *
 * [admitted] is `null` until a count has been taken, and the admitted own-asset set once one has — the
 * upload total `N` is its size. **Both are real, source-derived values** — there is no placeholder
 * count and no negative sentinel.
 *
 * ⚠️ **`null` and `0` are different answers, and conflating them is a shipped bug, not a hypothetical.**
 * `0` asserts that this membership contributes nothing; `null` asserts nothing at all. The status
 * projection settles to "In sync" when the synced count reaches the total, so a placeholder `0`
 * standing in for an unread count reads as *"everything shared"* on a device that has shared nothing
 * and has not looked — a checkmark on the one surface a member uses to decide whether their photos are
 * safe. This seam used to seed `0`, and that is exactly what members reported as a status going
 * backwards across launches (`SNAPSYNC-14`, `SNAPSYNC-16`): the settled frame was never true, and the
 * later "Synchronization ongoing…" was the first honest one.
 *
 * A source that has never been refreshed therefore reports `null`. A `SelectionPolicy.None` membership
 * reports a **counted** `0` — reached on its own branch, without enumerating — which settles the screen
 * exactly as it always has.
 *
 * The set is scoped by the membership's selection policy (capability `photo-selection-policy`); there
 * is no whole-library count. It carries normalized `assetId`s and nothing else — no per-asset state —
 * and it is ONE value, so the total and the set it is counted over can never come from different
 * refreshes. Status counts the ledger's per-photo done-ness over exactly this set (capability
 * `sync-status`), which is what keeps historical uploads from masking pending in-window photos.
 */
interface GalleryStatusSource {
    /** The admitted own-asset set whose size is the upload total `N`, or `null` when none was counted. */
    val admitted: StateFlow<Set<String>?>
}

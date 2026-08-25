package app.snapsync.ports

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the own-device upload total `N`: a level-triggered state holder whose current
 * value is always available synchronously, so the status projection never has to guess while waiting
 * for a first read.
 *
 * [size] is `null` until a count has been taken, and an `Int` once one has. **Both are real,
 * source-derived values** — there is no placeholder count and no negative sentinel.
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
 * The count is scoped by the membership's selection policy (capability `photo-selection-policy`); there
 * is no whole-library count. The seam exposes the count only — never individual assets, identity, or
 * per-asset state.
 */
interface GalleryStatusSource {
    /** The upload total `N`, or `null` when no count has been taken. */
    val size: StateFlow<Int?>
}

package app.snapsync.model

/**
 * What the upload discovery may read (capability `limited-photo-access`).
 *
 * [Unrestricted] — a full grant: discovery walks the library as ever. [Scoped] — a partial grant whose
 * selection HAS BEEN READ: discovery reads exactly that snapshot and MUST NOT walk, and the snapshot is an
 * **authoritative** walk — under a partial grant the selection is the gallery, so a photo it no longer
 * carries has left, and its rows go (de-selecting is deleting). [Unread] — a partial grant whose selection
 * has not been read yet: the app holds no selection at all, which is a different fact from an empty one.
 *
 * [Unread] is its own case because collapsing it into `Scoped(emptyList())` deletes: an authoritative empty
 * snapshot says every photo left, and an empty key resolution says every row's asset is gone. The app's
 * cycle is withheld while the scope is [Unread] (capability `upload-lifecycle`), so nothing reads it on
 * the upload path. Decision record: `changes/selection-is-the-walk` (D1).
 *
 * The value is derived, never stored: [selectionScope] below computes it from the current permission
 * and the latest selection snapshot — the composition supplies those two inputs and decides nothing —
 * so the walk-vs-snapshot decision has exactly one owner.
 */
sealed interface SelectionScope {
    data object Unrestricted : SelectionScope
    class Scoped(val resources: List<Resource>) : SelectionScope
    data object Unread : SelectionScope
}

/**
 * The derivation itself (capability `limited-photo-access`): current photo-access grant + the latest
 * selection snapshot → what discovery may read right now.
 *
 * Pure, and seated here rather than in the composition that calls it. It decides what a partial-grant
 * member may upload **at all** — under [PermissionStatus.LIMITED] the hand-picked selection IS the
 * membership's own-photo scope — which is a rule about the vocabulary, not a wiring choice; the
 * composition's job is to supply the two inputs, and it holds neither of them as a constant.
 *
 * A null [snapshot] is the gap between a grant turning partial (or a cold launch under one) and the first
 * observer emission: [SelectionScope.Unread]. Collapsing it to [SelectionScope.Unrestricted] would let a
 * partial-grant member's whole camera roll into someone else's event; collapsing it to an empty
 * [SelectionScope.Scoped] would delete the rows of every photo. An empty list is a read, empty selection,
 * and is `Scoped` like any other.
 *
 * Every non-`LIMITED` grant yields [SelectionScope.Unrestricted] — including `DENIED` / `NOT_DETERMINED`,
 * where there is nothing to read anyway: the scope says what discovery *may* consult, and refusing the read
 * is the permission-aware source's answer, not this one's.
 */
fun selectionScope(permission: PermissionStatus, snapshot: List<Resource>?): SelectionScope =
    if (permission == PermissionStatus.LIMITED) {
        snapshot?.let { SelectionScope.Scoped(it) } ?: SelectionScope.Unread
    } else {
        SelectionScope.Unrestricted
    }

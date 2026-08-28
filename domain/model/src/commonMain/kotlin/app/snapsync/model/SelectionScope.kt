package app.snapsync.model

/**
 * What the upload discovery may read (capability `limited-photo-access`).
 *
 * [Unrestricted] — a full grant: discovery walks the library as ever. [Scoped] — a partial grant:
 * discovery reads exactly the given selection snapshot and MUST NOT walk (an autonomous library read
 * under a partial grant queues the platform's limited-access alert; the read discipline allows only
 * in-flow reads, which is where the snapshot came from). A `Scoped(emptyList())` is the honest state
 * between a grant turning partial and the first snapshot arriving — discovery then finds nothing,
 * rather than walking.
 *
 * The value is derived, never stored: [selectionScope] below computes it from the current permission
 * and the latest selection snapshot — the composition supplies those two inputs and decides nothing —
 * so the walk-vs-snapshot decision has exactly one owner.
 */
sealed interface SelectionScope {
    data object Unrestricted : SelectionScope
    class Scoped(val resources: List<Resource>) : SelectionScope
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
 * A null [snapshot] is the honest gap between a grant turning partial and the first observer emission:
 * `Scoped(emptyList())`, so discovery finds nothing rather than walking. Collapsing it to
 * [Unrestricted] would let a partial-grant member's whole camera roll into someone else's event, which
 * is the inherited-default hazard this capability exists to close.
 *
 * Every non-`LIMITED` grant yields [Unrestricted] — including `DENIED` / `NOT_DETERMINED`, where there
 * is nothing to read anyway: the scope says what discovery *may* consult, and refusing the read is the
 * permission-aware source's answer, not this one's.
 */
fun selectionScope(permission: PermissionStatus, snapshot: List<Resource>?): SelectionScope =
    if (permission == PermissionStatus.LIMITED) {
        SelectionScope.Scoped(snapshot ?: emptyList())
    } else {
        SelectionScope.Unrestricted
    }

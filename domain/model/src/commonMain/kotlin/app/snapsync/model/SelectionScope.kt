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
 * The value is derived, never stored: the composition computes it from the current permission and the
 * latest selection snapshot, so the walk-vs-snapshot decision has exactly one owner.
 */
sealed interface SelectionScope {
    data object Unrestricted : SelectionScope
    class Scoped(val resources: List<Resource>) : SelectionScope
}

package app.snapsync.model

/**
 * Where a read's candidates came from — the fact that decides which rules judge them ([SelectionPolicy.over]).
 *
 * [Library]: a walk of the member's gallery (on Android, `DCIM` and its folders), where the app guesses on the member's
 * behalf and excludes what is certainly not an event photo. [Selection]: a partial grant's hand-picked selection, read
 * from its snapshot, where the member already chose — so only the capture range and echo suppression judge it.
 *
 * Stated by the read itself, never inferred from the grant afterwards: only the two snapshot paths say [Selection], so
 * a wrong label can only err toward [Library]'s stricter rules — a pass that under-admits and is healed by the next —
 * and never toward a library walk judged as a selection.
 */
enum class CandidateScope { Library, Selection }

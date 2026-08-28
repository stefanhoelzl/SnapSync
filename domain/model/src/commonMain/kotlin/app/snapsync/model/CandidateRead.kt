package app.snapsync.model

/**
 * What a library read for admission produced: the candidates, or the statement that there are none to
 * be had (capability `gallery-status`; law `module-architecture`, "Absence is never silent").
 *
 * ## Why this is not a list
 *
 * The seam used to return `List<Candidate>`, so **"the library could not be read" and "nothing
 * qualifies" were the same value**. Those two have opposite consequences. `EventPhotoSet.count()` feeds
 * the status total `N`, the projection settles once the synced count reaches `N`, and the screen renders
 * one health line rather than numbers — so a zero standing in for an unread library renders a check mark
 * meaning **"everything shared"** on a device that has read nothing. `sync-status` then forbids the
 * recovery that would hide it: once the projection is `Ready` it may not regress to `Loading`, so the
 * false frame is replaced by a worse-looking one rather than by a neutral one. That is the reported
 * defect `SNAPSYNC-14` / `SNAPSYNC-16`, which [app.snapsync.feature.status.OwnDeviceGalleryStatusSource]
 * closed for its own `Int?` while the seam underneath kept collapsing.
 *
 * ## Why not `Result`
 *
 * Three reasons, recorded so the question is not re-opened. ① It puts a **non-error in the error
 * channel** — a denied grant is not a failure, the read succeeded and the answer is "you may not have
 * one", and `Result.failure` would need a manufactured `Throwable` to carry a permission state. ② There
 * are **three** answers in the neighbourhood — a list (possibly empty), *not readable*, and *the walk
 * threw* — so two would fold into `failure` and be told apart by exception type: a sealed hierarchy in
 * disguise, without exhaustiveness. ③ `getOrNull()` / `getOrElse {}` are exactly the silent default that
 * produced the defect.
 *
 * The third answer stays deliberately **out** of this type: a thrown platform walk is a genuine failure,
 * caught where it happens, because putting it here would make the read carry the platform's faults.
 */
sealed interface CandidateRead {

    /**
     * The library was read: these are its candidates for [SelectionPolicy] admission.
     *
     * An empty list here is a real answer — *nothing qualifies* — and settles the screen exactly as any
     * other count does. A non-contributing membership and a member whose library holds only screenshots
     * both land here.
     */
    data class Readable(val candidates: List<Candidate>) : CandidateRead

    /**
     * **The admitted set cannot be stated right now.** Named for that consequence rather than for any
     * one cause, so that every cause with the same consequence reaches it — naming it for the grant is
     * what would leave the third case below quietly reporting an empty list.
     *
     * It absorbs three causes:
     *
     * - **`DENIED`** — the member withheld access.
     * - **`NOT_DETERMINED`** — the grant is unresolved.
     * - **`LIMITED` with no selection snapshot yet** — under a partial grant the hand-picked selection
     *   *is* the scope (capability `limited-photo-access`), and until the cold-launch baseline or the
     *   first observer emission has been consumed the app holds no selection and may not go looking.
     *   This is the only one of the three reachable on a shipped device in ordinary use, and it is the
     *   one a cause-shaped name would have missed.
     *
     * Collapsing the three is legitimate under the law's own test — *consequence asymmetry, not
     * nullability*: no consumer distinguishes them. The status total goes un-counted and the join
     * preview renders no row for all three, and that shared consequence is stated here. A cause may
     * still be carried to a device log as an opaque diagnostic, which no consumer may branch on — the
     * shape `SecureStoreRead.Unavailable` already uses.
     */
    data object NotReadable : CandidateRead
}

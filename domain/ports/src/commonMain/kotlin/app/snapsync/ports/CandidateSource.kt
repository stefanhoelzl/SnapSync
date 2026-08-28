package app.snapsync.ports

import app.snapsync.model.Candidate
import app.snapsync.model.CandidateRead
import app.snapsync.model.SelectionPolicy

/**
 * The **one** seam through which the photo library is read for admission (capability `gallery-status`).
 *
 * It takes the membership's [SelectionPolicy] — not a bound flattened out of it — for two reasons that are
 * really the same reason:
 *
 * ① **The platform can narrow its own query.** Given the policy's sealed rules, an implementation
 *   pattern-matches the ones its native query can express and ignores the rest. Because the rule set is
 *   sealed, adding a rule forces every translator to state explicitly whether it can express it, instead of
 *   silently not narrowing by it. A narrowing is an optimization only: the authoritative in-memory
 *   admission runs over whatever comes back, so a fetch can neither widen nor narrow the admitted set
 *   (capability `photo-selection-policy`).
 *
 * ② **Nothing has to relay it.** This replaced three stacked ports — a raw-asset walk, a resource
 *   enumeration over it, and the composition of the two — each taking a `since: String` and forwarding it
 *   through six pass-through layers to the single function that consumed it. The policy now arrives where
 *   it is used.
 *
 * ## The cost ladder
 *
 * A [Candidate] carries its asset's neutral facts and can fetch that asset's resources **on demand**.
 * Facts are plain in-memory platform properties; a resource read is one synchronous round-trip (~110 ms
 * per asset on an SE2). Since every selection rule decides on facts alone (capability
 * `photo-selection-policy`), a consumer that needs a count or the admitted asset set pays nothing, and one
 * that needs resources pays only for assets **already admitted** — filter-then-fetch, where the seam this
 * replaced fetched every in-scope asset's resources and then discarded the excluded ones.
 *
 * ## What this seam is not
 *
 * It carries no cursor. A resumable incremental walk needs `nextToken` / `removedAssetIds` /
 * `fullEnumeration`, which a count has no use for and a snapshot cannot honestly supply — that lives on
 * the upload seam (`BackgroundTransfer.discoverResources`). The app side cannot use a cursor anyway: a
 * cursor yields *changes*, while the status total needs a *count of the current set*, and maintaining that
 * from a change feed would require durable state — which is what the ledger already is.
 */
interface CandidateSource {

    /**
     * The library's candidate assets for [policy] — everything the admission should consider — or
     * [CandidateRead.NotReadable] when there is no admitted set to be had at all.
     *
     * An implementation MAY return a superset of the admitted set (its native narrowing is deliberately
     * widened at boundaries, and it cannot express every rule); it MUST NOT return a subset. The caller's
     * admission is authoritative over whatever comes back.
     *
     * **`Readable(emptyList())` and `NotReadable` are different answers and are never interchangeable.**
     * The first says the library was read and nothing in it qualifies — a counted zero, which settles the
     * status screen. The second says no answer exists yet. Every implementation states which it means;
     * none may arrive at the second by defaulting. This is the seam's half of the law "Absence is never
     * silent" (`module-architecture`), and it is not a hypothetical distinction: the collapse shipped as
     * `SNAPSYNC-14` / `SNAPSYNC-16`, and it survived under a partial grant after being fixed under a full
     * one. [CandidateRead] carries the whole account.
     *
     * A consumer therefore keeps **no grant check of its own**. Where candidates come from and whether
     * they can be produced at all are both answered here — splitting the two left each consumer restating
     * the grant distinction this seam already owns, and it is the restatement, not the reading, that lets
     * two paths drift apart (capability `limited-photo-access`).
     *
     * A [SelectionPolicy.None] membership SHOULD NOT reach here — callers short-circuit before enumerating,
     * because a walk costs one round-trip per asset and the direction already gave the empty answer.
     */
    suspend fun candidates(policy: SelectionPolicy): CandidateRead
}

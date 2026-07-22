package app.snapsync.model

/**
 * **What this membership contributes** (capability `photo-selection-policy`) — the per-membership inputs to
 * the selection policy, carried to *both* of its consumers as one value.
 *
 * The policy answers one question, and it has three inputs: the **cutoff** bounds *when* a photo was taken,
 * the **origin exclusions** bound *what it is*, and the participation **direction** bounds *whether at all*.
 * The first two were always policy inputs; the third was not, and each consumer improvised around its
 * absence — the upload cycle by ignoring it (uploading a download-only member's camera roll), the status
 * total by ignoring it too (leaving the screen to force-hide an arrow over a total that would never settle).
 * `Contribution` makes it the input it always was. Decision record: `changes/archive/…-fix-upload-direction-gate`.
 *
 * It lives in `model/` for the same reason the rest of the policy does: feature/upload and
 * feature/status must apply the **identical** rules, and this is the only zone both can see. It carries
 * primitives — no config-store dependency follows it here; the composition maps
 * `EventConfig.direction` + `minPhotoDate` onto it.
 *
 * **Two constructors, not a cutoff plus a flag.** A `(cutoff, uploadsEnabled)` pair can express *"contributes
 * nothing, and here is the cutoff it is not using"* — a state with no meaning. [None] carries no cutoff
 * because a non-contributor has none, so that state cannot be built.
 *
 * **Required everywhere, with no default — in either polarity.** This is not fastidiousness; both defaults
 * are catastrophic in opposite directions:
 * - a permissive default (`Since("")`) uploads the **entire library from the beginning of time** — `""`
 *   compares `>=` true against every `creationDate`;
 * - a fail-closed default ([None]) is **worse**, because it is silent: a contributing member would share
 *   nothing, `N` would read `0`, and the screen would read "In sync" while nothing happened. *"An event photo
 *   that silently fails to upload is invisible and unfixable."*
 *
 * So there is no safe value to default to, and the type offers none. Every call site states its posture, the
 * way `reconcile` and the cutoff before it already had to.
 *
 * **Per membership, never per resource.** [None] SHALL short-circuit *before* any library walk. The walk costs
 * one synchronous PhotoKit round-trip **per asset** (~110 ms on an SE2), so expressing "contributes nothing"
 * as a per-asset filter would spend minutes of XPC on a 4000-photo library to arrive at the empty set. A
 * per-resource rule structurally cannot say "don't start".
 */
sealed interface Contribution {

    /**
     * The membership contributes nothing — its participation direction excludes upload (`DownloadOnly`).
     *
     * Carries no cutoff: a non-contributor has none to speak of. Both consumers reach the empty answer
     * without enumerating — the upload cycle declines before discovery, the status total reports `0`.
     */
    data object None : Contribution

    /**
     * The membership contributes every policy-admitted asset captured **within** the capture-date range
     * `[cutoff, until]` — at or after [cutoff] and at or before [until].
     *
     * [cutoff] is the per-device, per-membership capture-date **lower** bound, already clamped to
     * `max(chosen, startsAt)` at join. [until] is the **upper** bound (the ceiling), already clamped to
     * `min(chosen, endsAt)` at join — or **`null`** when the membership carries no ceiling (unbounded: a
     * legacy membership persisted before the range existed, before the reconcile backfill supplies one;
     * capability `event-rejoin-reconciliation`). A `null` upper bound admits every capture date, consistent
     * with admit-on-doubt — never the fail-closed direction. Both are compared lexically against a
     * resource's `creationDate`, so they must be in the cutoff string format the policy pins (see
     * `photo-selection-policy`, "Cutoff string format invariant"). The origin exclusions apply on top; this
     * type carries only the scalars a membership chooses.
     */
    data class Since(val cutoff: String, val until: String?) : Contribution

    companion object {
        /**
         * Map a membership's two facts onto a [Contribution] — the **one** place that decision is made.
         *
         * Every caller is a composition root (both iOS tiers, the world, the desktop harnesses), and the
         * project's hard rule declares those wiring-only and untested. Left to bind it themselves, each would
         * carry its own `if (direction.includesUpload) … else …` — five copies of a privacy decision, in the
         * five files no test can reach. That is not a hypothetical failure mode: the download arm's
         * equivalent root binding is where a `?: true` silently answered "enabled" for a membership that did
         * not exist.
         *
         * So the roots pass **facts** — [includesUpload] read off `EventConfig.direction`, [cutoff] off
         * `EventConfig.minPhotoDate`, [until] off `EventConfig.maxPhotoDate` (`null` when the membership has
         * no ceiling yet) — and this function, which is tested, makes the decision. Primitives in, so this
         * stays primitives-in (the same reason `DownloadController` takes a plain predicate).
         */
        fun of(includesUpload: Boolean, cutoff: String, until: String?): Contribution =
            if (includesUpload) Since(cutoff, until) else None
    }
}

/** The cutoff to scope a walk by, or `null` when nothing is contributed and no walk should begin. */
val Contribution.cutoffOrNull: String?
    get() = when (this) {
        Contribution.None -> null
        is Contribution.Since -> cutoff
    }

/**
 * The upper capture-date bound (ceiling) to scope a walk by, or `null` when there is none — either the
 * membership contributes nothing ([None]) or it carries an unbounded ceiling ([Since.until] `== null`).
 * A `null` here means "no upper filter", never "exclude everything".
 */
val Contribution.untilOrNull: String?
    get() = when (this) {
        Contribution.None -> null
        is Contribution.Since -> until
    }

/** Whether this membership contributes at all — `false` short-circuits before any enumeration. */
val Contribution.uploads: Boolean
    get() = this !is Contribution.None

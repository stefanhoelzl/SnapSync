package app.snapsync.rig.gallery

/**
 * What a seed is *for*. Two shapes, because they answer different questions and neither substitutes.
 *
 * They were two separate launch variables (`SNAPSYNC_SEED_PHOTOS` / `SNAPSYNC_SEED_POLICY`) only because an
 * environment variable takes no parameters. They are one function with a boolean and are now one command
 * with a parameter.
 */
enum class SeedKind {

    /**
     * `n` tiny assets dated 2001 — the large-library / walk-cost seed. The gallery walk's cost is per
     * **asset** (one synchronous PhotoKit XPC round-trip each), which is exactly what the capture-date
     * bound exists to contain, and what a one-photo dev device cannot demonstrate. These never upload.
     */
    BULK,

    /**
     * `n` assets dated **an hour ahead**, alternating **above** and **below** the 3 MP image floor — the
     * selection-policy probe (capability `photo-selection-policy`).
     *
     * It exists because neither an empty library nor a bulk seed can exercise the policy on a real device:
     *
     * - a dev device may hold **no real photos at all** (the SE2 does not), so there is nothing the policy
     *   should *admit*, and a run cannot distinguish "the policy correctly excluded everything" from "the
     *   fetch predicate silently returned nothing" — which, given that the wrong predicate form returns
     *   **zero rows without raising**, is exactly the confusion that matters most;
     * - and the bulk seed's assets are dated 2001, so the **cutoff** excludes them before the origin
     *   rules are ever consulted.
     *
     * Dating them ahead of *now* puts them past any cutoff an event created today can carry (the cutoff is
     * clamped to `max(chosen, startsAt)`, capability `join-event`), so the **only** thing that can separate
     * them is the resolution floor. One seed then answers every question at once: the walk returns assets
     * (the predicate is not silently empty), exactly the below-floor half is origin-excluded, `N` counts
     * only the rest, and only the rest uploads.
     */
    POLICY,

    /**
     * SPIKE (throwaway): `n` assets dated an hour ahead, all **above** the floor, rendered as high-frequency
     * NOISE rather than a flat colour — so the JPEG does not compress and each asset lands in the megabytes
     * instead of ~51 KB.
     *
     * Every other kind renders a flat fill, which encodes to ~51 KB regardless of pixel dimensions. That is
     * deliberate and right for the questions they answer (walk cost, policy admission), but it makes them
     * useless for any question about BYTES ON THE WIRE: at 51 KB a request body is delivered before a server
     * could plausibly answer, so upload timing, early responses and resumability are all unmeasurable.
     */
    NOISE,
}

/** What a seed did, for the command's response. */
class SeedOutcome(val requested: Int, val created: Int, val kind: SeedKind, val failedAtChunk: Int?)

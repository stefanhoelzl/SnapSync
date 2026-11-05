package app.snapsync.architecture

import app.snapsync.architecture.AcceptedUnclaimed.Kind.MAY
import app.snapsync.architecture.AcceptedUnclaimed.Kind.PLATFORM
import app.snapsync.architecture.AcceptedUnclaimed.Kind.PROCESS

/**
 * **The scenarios left unclaimed on purpose** — what `verifiesReport` leaves out, so the report shows only work still
 * to do. [AcceptedUnclaimedTest] keeps every entry pointing at a scenario that exists and that no test claims.
 *
 * It lives here and never in `openspec/`: the specs are the contract of record, edited only through an OpenSpec
 * change, and why a scenario has no test is engineering, not contract.
 *
 * It is NOT a to-do list. A scenario waiting for a lever, a world event or a screen test stays in the report. And
 * what the api or a web page decides is claimed by the Deno suites, never listed.
 */
internal object AcceptedUnclaimed {

    enum class Kind {
        /** No test of ours can make the platform, a browser or a store do it: the outcome is theirs. */
        PLATFORM,

        /**
         * An accepted gap: the outcome is "may". A test pinning it would turn the gap into a promise (the rule set when
         * the app's specs were claimed).
         */
        MAY,

        /**
         * An obligation on how the product is released, not an outcome anything does at run time: no test can observe
         * it hold. Kept rare; a scenario some code decides is never this.
         */
        PROCESS,
    }

    class Entry(val spec: String, val requirement: String, val scenario: String, val kind: Kind, val reason: String)

    val entries: List<Entry> = listOf(
        // ── MAY: every scenario whose outcome is an accepted "may" ─────────────────────────────────────────────
        Entry(
            "receiving-photos", "A deleted received photo never comes back", "A reinstall may receive again", MAY,
            "a reinstall forgets what was deleted; receiving it again is the accepted gap",
        ),
        Entry(
            "receiving-photos", "A deleted received photo never comes back",
            "A reinstall under limited access may receive again", MAY,
            "photos outside the selection cannot be recognised after a reinstall; receiving them again is accepted",
        ),
        Entry(
            "receiving-photos", "Received photos are never shared back", "A reinstall may share received photos back",
            MAY, "a photo an unmarking version received cannot be recognised; sharing it back is the accepted gap",
        ),
        Entry(
            "receiving-photos", "Received photos are never shared back",
            "A reinstall may share photos received in another event", MAY,
            "only the joined event's photos are read; another event's are the accepted gap",
        ),

        // ── PROCESS ───────────────────────────────────────────────────────────────────────────────────────────
        Entry(
            "privacy-security", "The Privacy Policy states what leaves the device",
            "A new kind of data starts leaving the device", PROCESS,
            "a release obligation: the policy is updated with the release that starts sending; no run shows it",
        ),
    )
}

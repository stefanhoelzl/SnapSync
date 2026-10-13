package app.snapsync.ports

import app.snapsync.inbound.Running

/**
 * The app process's **background time**: "keep this process running while I finish, and tell me when time is up"
 * (`docs/architecture.md`, "Background time is an outbound port named for the need"; decision record
 * `changes/own-work-per-wake`, D3 and D5).
 *
 * A silent push and a background-transfer wake carry no expiry signal of their own, so the core takes a hold here no
 * later than it is handed such a wake's completion, and the operating system's signal — [begin]'s `onExpiry` — is
 * the only notion of "time is up" the app acts on. That is why the surface carries **no duration, no remaining-time
 * read and no estimate**: an estimate is not a signal, and a number here would invite a deadline of the app's own,
 * which is what cut import batches short in the field (iPhone XS, iOS 18.7.9: a self-chosen 20 s release, then
 * suspension ≤ 0.4 s later).
 *
 * Background time is **per app**, not additive per hold: a hold taken while another is active costs no time, so the
 * core may take one for each wake without accounting between them.
 *
 * App process only. The upload extension has no such signal to offer — measured, its only end is a hard kill — so
 * this port is not bound in, linked into or faked for its composition.
 */
interface BackgroundTime : Port {

    /**
     * Begin a hold, labelled [label] for the diagnostic line, and return it.
     *
     * [expiry] is the run the hold is for: the adapter calls [Running.expire] when the operating system says this
     * hold's time is up. At most once, on a thread the implementation does not choose (the main thread on iOS), and
     * possibly **before [begin] returns** — a process whose time is already up is refused a hold, and that refusal
     * is reported as an immediate expiry rather than as an error, because it means the same thing to the caller.
     * [Running.expire] returns at once: the operating system expects its handler back promptly.
     *
     * An expiry does **not** end the hold by itself: the run ends it with [BackgroundTimeHold.end] once its
     * cancellation has unwound, or its grace has run out. A hold that is never ended is, per Apple, a termination.
     */
    fun begin(label: String, expiry: Running): BackgroundTimeHold
}

/** One hold [BackgroundTime.begin] granted. Its only operation is ending it. */
interface BackgroundTimeHold {

    /**
     * End this hold. The first call ends it with the operating system — whether the work finished or an expiry
     * stopped it — and every later call does nothing, so a caller that ends on more than one path cannot end a hold
     * twice (or end another hold that happens to reuse the platform's identifier).
     */
    fun end()
}

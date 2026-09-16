package app.snapsync.feature.upload

/**
 * Which `REQUESTED` keys a stranded pass records `FAILED` — the transfers that were lost (capability
 * `ios-url-session-upload`, "Stranded reconciliation: scoped each cycle, complete at a start").
 *
 * Each is recorded `FAILED` so a later cycle re-uploads it. This is why the app-driven tier recovers from process
 * death at all: a task the OS dropped, or a force-quit cancelled, delivers **no completion**, so nothing else will
 * ever move that row — and the engine never re-issues a `REQUESTED` key, so without this the photo is abandoned
 * silently and permanently. The transport reports what it holds and what it lost; these rules, and the write, are
 * the cycle's.
 *
 * There are two rules because there are two moments, and each is only true at its own:
 *
 * - [strandedEachCycle] — every cycle, only the `REQUESTED` keys the transport says it **began and lost**. A
 *   `REQUESTED` row it never began may belong to another transport still carrying it, so no amount of "no live
 *   transfer here" makes it this cycle's to demote.
 * - [strandedAtStart] — once, after a mechanism start, every `REQUESTED` key with **no live transfer**. At a start
 *   no other transport is carrying rows, so the wider rule is true there and recovers rows whose lost-transfer
 *   marker is already gone.
 *
 * [pending] is `REQUESTED` rows, exactly — never the whole non-settled backlog. A `FAILED` row has already
 * been adjudicated, and re-reporting it every cycle writes the row again, signals a change, and claims a
 * loss that did not happen: a field log shows one key "stranded" twelve times inside a single process,
 * seven of them within sixteen seconds.
 *
 * Pure set arithmetic over `String` — no platform type reaches it, which is what makes the recovery rules
 * assertable without a session, a task, or a device.
 */
internal fun strandedEachCycle(pending: Set<String>, lost: Set<String>): List<String> =
    pending.filter { it in lost }

/** The restart rule: every `REQUESTED` key the transport holds no live transfer for. See [strandedEachCycle]. */
internal fun strandedAtStart(pending: Set<String>, live: Set<String>): List<String> =
    pending.filter { it !in live }

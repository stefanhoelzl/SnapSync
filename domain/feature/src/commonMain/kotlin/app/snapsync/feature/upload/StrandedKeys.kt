package app.snapsync.feature.upload

/**
 * The `REQUESTED` keys a transport no longer holds a transfer for — the transfers that were lost.
 *
 * Each is recorded `FAILED` so a later enumeration re-uploads it. This is why the app-driven tier recovers from
 * process death at all: a task the OS dropped, or a force-quit cancelled, delivers **no completion**, so nothing
 * else will ever move that row — and the engine never re-issues a `REQUESTED` key, so without this subtraction
 * the photo is abandoned silently and permanently. The transport reports what it holds
 * (`BackgroundTransfer.liveKeys`); this rule, and the write, are the cycle's (capability
 * `ios-url-session-upload`, "Precise in-flight reconciliation replaces blanket clear").
 *
 * [pending] is `REQUESTED` rows, exactly — never the whole non-settled backlog. A `FAILED` row has already
 * been adjudicated, and re-reporting it every cycle writes the row again, signals a change, and claims a
 * loss that did not happen: a field log shows one key "stranded" twelve times inside a single process,
 * seven of them within sixteen seconds.
 *
 * There is no third term. This used to subtract the completions *drained this round* as well, because a
 * completion lived in memory until a cycle collected it, and a key could be both `REQUESTED` and already
 * finished. It cannot be any more — the outcome is recorded the moment the platform reports it, so a
 * finished key is no longer `REQUESTED` and never enters this set.
 *
 * Pure set arithmetic over `String` — no platform type reaches it, which is what makes the recovery rule
 * assertable without a session, a task, or a device.
 */
internal fun strandedKeys(pending: Set<String>, live: Set<String>): List<String> =
    pending.filter { it !in live }

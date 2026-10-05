package app.snapsync.model

/**
 * The silent-push payload's event id (capability `receiving-photos`). The Swift shell forwards the
 * OS-delivered `userInfo` dictionary **whole** (the transcriber law, `docs/architecture.md`
 * "Shells are wiring only" — migration step 12; the field extraction used to be a `guard` in Swift,
 * where nothing could test it); this codec is the one place that knows the payload's shape.
 *
 * `null` when the payload carries no usable `eventId` — the flow then fans out to no arm and the OS
 * completion is still released (a malformed push must never strand the handler).
 *
 * Absence: null means "this push names no event". The collapse is deliberate and **safe for
 * every cause it absorbs** (`docs/architecture.md`,
 * "Absence is never silent"): an absent `eventId` key and a present-but-not-a-`String` one are both
 * "this push names no event", and both lead to the identical outcome — no fan-out, handler released.
 * There is no third cause hiding here: the payload is an already-materialised dictionary, so reading
 * it cannot fail. Nor is the outcome silent — `SilentPush.run` logs it before returning, which is the
 * clause of that law an entry point may never trade away.
 */
fun pushEventId(userInfo: Map<Any?, *>): String? = userInfo["eventId"] as? String

/**
 * The union position a silent push announces (decision record `changes/incremental-union`, D6): a device whose cursor
 * already stands there reads nothing. APNs carries it as a JSON number, which reaches here as whatever number type the
 * platform's dictionary bridged it to; FCM as a string, like every data value. `null` when the push names none — the
 * close wake, an older backend, or a value that is no position — and the push then reads as it always did.
 */
fun pushSeq(userInfo: Map<Any?, *>): Long? = when (val value = userInfo["seq"]) {
    null -> null
    is Long -> value
    is Int -> value.toLong()
    is Number -> value.toDouble().takeIf { it == kotlin.math.floor(it) }?.toLong()
    // A platform number type that is not a Kotlin Number still prints as its digits.
    else -> value.toString().toLongOrNull()
}

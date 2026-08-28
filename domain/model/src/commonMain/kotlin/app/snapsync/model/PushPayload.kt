package app.snapsync.model

/**
 * The silent-push payload's event id (capability `push-registration`). The Swift shell forwards the
 * OS-delivered `userInfo` dictionary **whole** (the transcriber law, spec `module-architecture`
 * "Shells are wiring only" — migration step 12; the field extraction used to be a `guard` in Swift,
 * where nothing could test it); this codec is the one place that knows the payload's shape.
 *
 * `null` when the payload carries no usable `eventId` — the flow then fans out to no arm and the OS
 * completion is still released (a malformed push must never strand the handler).
 *
 * Absence: null means "this push names no event". The collapse is deliberate and **safe for
 * every cause it absorbs** (spec `module-architecture`,
 * "Absence is never silent"): an absent `eventId` key and a present-but-not-a-`String` one are both
 * "this push names no event", and both lead to the identical outcome — no fan-out, handler released.
 * There is no third cause hiding here: the payload is an already-materialised dictionary, so reading
 * it cannot fail. Nor is the outcome silent — `SilentPush.run` logs it before returning, which is the
 * clause of that law an entry point may never trade away.
 */
fun pushEventId(userInfo: Map<Any?, *>): String? = userInfo["eventId"] as? String

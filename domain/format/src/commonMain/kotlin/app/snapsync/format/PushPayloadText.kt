package app.snapsync.format

import app.snapsync.domain.event.EventId
import kotlin.math.floor

/**
 * **What a silent push names**, read from the payload the platform delivers. The Swift shell forwards the OS-delivered
 * `userInfo` dictionary **whole** (the transcriber law, `docs/architecture.md` "Shells are wiring only"); this is the
 * one place that knows the APNs and FCM payload's shape.
 */
object PushPayloadText {
    /**
     * The event the push names — `null` when the payload carries no usable `eventId`: the push then reaches no arm and
     * the OS completion is still released (a malformed push must never strand the handler).
     *
     * An absent `eventId` key and a present-but-not-a-`String` one are both "this push names no event", and both lead
     * to the identical outcome — no fan-out, handler released (`docs/architecture.md`, "Absence is never silent").
     * There is no third cause: the payload is an already-materialised dictionary, so reading it cannot fail. Nor is the
     * outcome silent — the entry point logs it before returning.
     */
    fun eventId(userInfo: Map<Any?, *>): EventId? = (userInfo["eventId"] as? String)?.let(EventId::parse)

    /**
     * The union position the push announces (decision record `changes/incremental-union`, D6): a device whose cursor
     * already stands there reads nothing. APNs carries it as a JSON number, which reaches here as whatever number type
     * the platform's dictionary bridged it to; FCM as a string, like every data value. `null` when the push names none
     * — the close wake, an older backend, or a value that is no position — and the push then reads as it always did.
     */
    fun seq(userInfo: Map<Any?, *>): Long? = when (val value = userInfo["seq"]) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toDouble().takeIf { it == floor(it) }?.toLong()
        // A platform number type that is not a Kotlin Number still prints as its digits.
        else -> value.toString().toLongOrNull()
    }
}

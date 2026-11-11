package app.snapsync.rig

/**
 * The payload a played silent push hands the app's push service, as APNs delivers the backend's wake: the event, and
 * the union position it announces (`seq`, a JSON number) — absent when the push names none. One builder for every
 * host that plays a push, so a mocked push service and the iOS app host hand over the same shape.
 */
object PlayedPushPayload {
    fun of(eventId: String?, announced: Long?): Map<Any?, Any?> = buildMap {
        put("eventId", eventId)
        announced?.let { put("seq", it) }
    }
}

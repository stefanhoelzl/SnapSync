package app.snapsync.format

import app.snapsync.domain.process.FootprintSample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The trail of the app's own memory readings, as the file in its private area keeps it: a JSON array of readings, each
 * `{"atEpochMillis", "moment", "footprintBytes", "peakBytes"?, "headroomBytes"?}` — an unknown reading leaves out,
 * never writes as `null`. Every build since the trail began wrote exactly this, so a trail an older build left reads as
 * one.
 */
object FootprintTrailText {
    fun encode(trail: List<FootprintSample>): String = buildJsonArray {
        trail.forEach { sample ->
            add(
                buildJsonObject {
                    put(AT, sample.atEpochMillis)
                    put(MOMENT, sample.moment)
                    put(FOOTPRINT, sample.footprintBytes)
                    sample.peakBytes?.let { put(PEAK, it) }
                    sample.headroomBytes?.let { put(HEADROOM, it) }
                },
            )
        }
    }.toString()

    /**
     * The trail [text] holds, or an empty one for text that is not a trail. Unreadable readings are worth less than the
     * report they would ride with, so they never stand in its way.
     */
    fun decode(text: String): List<FootprintSample> {
        val readings = parsed(text) as? JsonArray ?: return emptyList()
        val samples = readings.map { (it as? JsonObject)?.let(::sample) }
        return samples.filterNotNull().takeIf { it.size == samples.size }.orEmpty()
    }

    private fun sample(reading: JsonObject): FootprintSample? {
        val at = reading.long(AT) ?: return null
        val moment = (reading[MOMENT] as? JsonPrimitive)?.takeIf { it.isString } ?: return null
        val footprint = reading.long(FOOTPRINT) ?: return null
        return FootprintSample(at, moment.content, footprint, reading.long(PEAK), reading.long(HEADROOM))
    }

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    /** [text] as JSON, or `null` for text that is not: the library answers that by throwing. */
    private fun parsed(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        // A `SerializationException` is one: text that is not JSON.
        null
    }

    private const val AT = "atEpochMillis"
    private const val MOMENT = "moment"
    private const val FOOTPRINT = "footprintBytes"
    private const val PEAK = "peakBytes"
    private const val HEADROOM = "headroomBytes"
}

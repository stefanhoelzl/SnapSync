package app.snapsync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * What the process's own memory accounting says right now (capability `privacy-security`; the read is
 * `ports/ProcessInfo`).
 *
 * It exists for one question a process-metric report cannot answer: the platform tells us, a day late, that a
 * suspended app was ended for memory and what its footprint averaged — never what it was at the moment it was
 * suspended, and never whether it had spiked. iOS sends no per-incident diagnostic for such an exit. So the app
 * records its own footprint as it settles in the background ([FootprintSample]), and the next report carries the
 * last few readings ([footprintFields]).
 */
data class MemoryFootprint(
    /** What the platform charges this process for — the figure a memory-pressure exit is decided on. */
    val footprintBytes: Long,
    /** The highest footprint this process has reached in its life, or `null` where the platform does not say. */
    val peakBytes: Long? = null,
    /** How far the process is from its own memory limit, or `null` where the platform does not say. */
    val headroomBytes: Long? = null,
)

/** One reading of [MemoryFootprint], when it was taken and at which [moment] (`"entering background"`, …). */
@Serializable
data class FootprintSample(
    val atEpochMillis: Long,
    val moment: String,
    val footprintBytes: Long,
    val peakBytes: Long? = null,
    val headroomBytes: Long? = null,
) {
    constructor(at: Instant, moment: String, footprint: MemoryFootprint) : this(
        at.toEpochMilliseconds(), moment, footprint.footprintBytes, footprint.peakBytes, footprint.headroomBytes,
    )

    /**
     * One line, in kB — the unit the platform's own report states its memory in (`"83574 kB"`), so a reading and the
     * report's average compare at a glance.
     */
    fun describe(): String = buildString {
        append(Instant.fromEpochMilliseconds(atEpochMillis)).append(' ').append(moment)
        append(": footprint ").append(kB(footprintBytes))
        peakBytes?.let { append(", peak ").append(kB(it)) }
        headroomBytes?.let { append(", headroom ").append(kB(it)) }
    }

    private fun kB(bytes: Long) = "${bytes / BYTES_PER_KB} kB"
}

/**
 * How many readings are kept: the newest is the one that matters — the footprint the process last settled at before
 * it was suspended — and the few before it are what tell a spike from a footprint that was always that high.
 */
const val FOOTPRINT_TRAIL_LENGTH: Int = 8

/** The key prefix the readings ride under in a process-metric report's context: `ownFootprint.0` is the newest. */
const val FOOTPRINT_FIELD_PREFIX: String = "ownFootprint"

/** [trail] with [sample] added, the oldest dropped past [FOOTPRINT_TRAIL_LENGTH]. */
fun appendedFootprint(trail: List<FootprintSample>, sample: FootprintSample): List<FootprintSample> =
    (trail + sample).takeLast(FOOTPRINT_TRAIL_LENGTH)

/** The trail as the report's context carries it, newest first: `ownFootprint.0` is the last reading taken. */
fun footprintFields(trail: List<FootprintSample>): Map<String, String> =
    trail.asReversed().withIndex().associate { (index, sample) -> "$FOOTPRINT_FIELD_PREFIX.$index" to sample.describe() }

fun encodeFootprintTrail(trail: List<FootprintSample>): String = trailJson.encodeToString(trail)

/**
 * The trail a file holds, or an empty one for text that is not a trail. Unreadable readings are worth less than the
 * report they would ride with, so they never stand in its way.
 */
fun decodeFootprintTrail(text: String): List<FootprintSample> =
    try {
        trailJson.decodeFromString<List<FootprintSample>>(text)
    } catch (_: IllegalArgumentException) {
        // A `SerializationException` is one: text that is not a trail.
        emptyList()
    }

private val trailJson = Json { ignoreUnknownKeys = true }

private const val BYTES_PER_KB = 1_000L

package app.snapsync.rig

import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The `/device` write commands BOTH hosts honour, with one request and response shape (capability
// `docs/testing.md`, "One control protocol, served by two hosts"). Each host supplies only the act; the
// parsing, the refusals and the rendering are here, so the two cannot drift into two dialects of one verb.

/**
 * `POST /device/reset` — void durable sync state through the build's development controls' [reset].
 *
 * It answers that the reset ran, and nothing about the ledger: the ledger is the app's own bookkeeping, which the
 * channel no longer reads (`docs/testing.md`, "The control channel"). What a reset changed is observed where a person
 * would see it — the screen, and the uploads that follow.
 */
fun resetCommand(reset: suspend () -> Unit): RigCommand = RigCommand { _, _ ->
    reset()
    CommandResult.ok("""{"reset":true}""")
}

/**
 * `POST /device/gallery/seed?n=&kind=` — seed [SeedKind]-shaped assets through the host's [seed].
 *
 * A kind the host cannot honour answers `409` with the reason the host gives ([SeedRefused]), never a seed of
 * some other kind: a caller that asked for bytes on the wire and silently got flat fills would measure nothing.
 */
fun seedCommand(seed: suspend (n: Int, kind: SeedKind) -> SeedOutcome): RigCommand = RigCommand { params, _ ->
    val n = params["n"]?.toIntOrNull()
    val kind = SeedKind.entries.firstOrNull { it.name.equals(params["kind"], ignoreCase = true) }
    when {
        n == null || n <= 0 -> CommandResult.badRequest("n must be a positive integer, was '${params["n"]}'")
        kind == null -> CommandResult.badRequest(
            "kind must be one of ${SeedKind.entries.joinToString("|") { it.name.lowercase() }}, " +
                "was '${params["kind"]}'",
        )
        else -> try {
            val outcome = seed(n, kind)
            CommandResult.ok(
                """{"requested":${outcome.requested},"created":${outcome.created},""" +
                    """"kind":"${outcome.kind.name.lowercase()}","failedAtChunk":${outcome.failedAtChunk}}""",
            )
        } catch (refused: SeedRefused) {
            CommandResult.refused(refused.message.orEmpty())
        }
    }
}

/** A seed kind this host cannot produce, with the reason — answered `409` by [seedCommand]. */
class SeedRefused(reason: String) : Exception(reason)

/** Refuse a seed with [why] — answered `409` by the command that ran it. */
fun refused(why: String): Nothing = throw SeedRefused(why)

/**
 * `POST /device/gallery/add-moving` with a [MovingPhoto] body — ONE photo that moves, added to the library as the
 * phone's own camera took it, from the real files a camera wrote: on iPhone a still and its paired movie, one Live
 * Photo; on Android one motion-photo file. Real bytes are the point: a synthesized seed cannot stand for what a camera
 * writes, and the moving photo's form is exactly what the share and receive journeys are about.
 *
 * Answers the asset's id. A shape the host's library cannot hold answers `409` with the reason ([SeedRefused]).
 */
fun addMovingCommand(add: suspend (MovingPhoto) -> String): RigCommand = RigCommand { _, body ->
    val photo = body?.let { runCatching { movingJson.decodeFromString(MovingPhoto.serializer(), it) }.getOrNull() }
    when {
        photo == null -> CommandResult.badRequest("the body must be a moving photo: {date, files: [{name, data}]}")
        photo.files.isEmpty() -> CommandResult.badRequest("a moving photo has at least one file")
        else -> try {
            CommandResult.ok("""{"added":${jsonString(add(photo))}}""")
        } catch (refused: SeedRefused) {
            CommandResult.refused(refused.message.orEmpty())
        }
    }
}

/** [add-moving][addMovingCommand]'s body: the capture [date] (ISO-8601 instant) and the camera's [files]. */
@Serializable
data class MovingPhoto(val date: String, val files: List<MovingFile>)

/** One file a camera wrote: its [name] as the camera named it, its bytes as base64 [data]. */
@Serializable
data class MovingFile(val name: String, val data: String) {
    @OptIn(ExperimentalEncodingApi::class)
    fun bytes(): ByteArray = Base64.decode(data)

    /** A movie by its extension — the paired video of a Live Photo. */
    val isMovie: Boolean get() = name.substringAfterLast('.').lowercase() in setOf("mov", "mp4")
}

private val movingJson = Json { ignoreUnknownKeys = false }

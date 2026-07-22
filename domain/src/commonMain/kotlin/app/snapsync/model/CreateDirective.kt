@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The `SNAPSYNC_CREATE_EVENT` developer launch-environment payload (capability `ios-app-shell`): a
 * `base64url(JSON)` blob describing an event to mint headlessly. Unlike the [EventLinkPayload] wire type
 * (which the invite QR carries and a browser must never see), this is a **dev/test-only** trigger, so it
 * is plain `base64url(JSON)` with no URL wrapper — base64url purely to spare the shell from quoting a
 * [name] that may contain spaces, quotes, or emoji.
 *
 * It is decoded by [decodeCreateDirective], the one authoritative codec, so parsing is tested on JVM and
 * `iosSimulatorArm64` alike (the shell reads none of these fields by hand). Every field but [name] is
 * optional:
 *
 * - [name] — **required**, the event name sent to `POST /events` (trimmed by the use-case).
 * - [startsAt] — the event's start date (canonical UTC `…Z`). Absent → the mint uses "now" (the floor
 *   for every joiner's cutoff, capability `photo-selection-policy`).
 * - [autoJoin] — when `true`, the minted event is joined immediately (the use-case forwards a synthesized
 *   `autoJoin` [EventLinkPayload] link through the existing join gate); when `false` (default), the event
 *   is **mint-only** and its id is logged (`created eventId=<uuid>`).
 * - [minPhotoDate] / [direction] / [saveToAlbum] — the membership overrides carried into the synthesized
 *   join link on an [autoJoin] mint (they mean nothing mint-only). Same semantics and same floor clamp as
 *   the [EventLinkPayload] keys of the same name.
 *
 * The serializer is **strict** (`ignoreUnknownKeys = false`), so an unknown key is a decode failure, never
 * a silent partial — matching [decodeEventUrl].
 */
@Serializable
class CreateEventPayload(
    val name: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val autoJoin: Boolean = false,
    val minPhotoDate: String? = null,
    val maxPhotoDate: String? = null,
    val direction: String? = null,
    val saveToAlbum: Boolean? = null,
)

/** The outcome of decoding a `SNAPSYNC_CREATE_EVENT` payload: a valid payload, or a typed reason. */
sealed interface CreateDecodeResult {
    data class Success(val payload: CreateEventPayload) : CreateDecodeResult
    data class Failure(val reason: String) : CreateDecodeResult
}

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val createJson = Json { ignoreUnknownKeys = false; isLenient = false }

/** Base64url decoding accepts padding or not (mirrors [decodeEventUrl]). */
private val createDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * Decodes a raw `base64url(JSON)` `SNAPSYNC_CREATE_EVENT` value into a [CreateEventPayload], performing
 * structural-only validation (valid base64url, UTF-8 JSON with the required `name` key plus the optional
 * `startsAt`/`autoJoin`/`minPhotoDate`/`direction`/`saveToAlbum` keys and no other, `name` non-blank, and
 * `direction` — when present — one of the known [Direction.wire] tokens) and **no** network I/O. Never
 * throws: every deviation becomes a [CreateDecodeResult.Failure].
 */
fun decodeCreateDirective(raw: String): CreateDecodeResult {
    val jsonBytes = try {
        createDecoder.decode(raw.trim())
    } catch (_: IllegalArgumentException) {
        return CreateDecodeResult.Failure("payload is not valid base64url")
    }

    val payload = try {
        createJson.decodeFromString(CreateEventPayload.serializer(), jsonBytes.decodeToString())
    } catch (_: SerializationException) {
        return CreateDecodeResult.Failure("payload is not valid create JSON")
    } catch (_: IllegalArgumentException) {
        return CreateDecodeResult.Failure("payload is not valid create JSON")
    }

    if (payload.name.isBlank()) return CreateDecodeResult.Failure("create payload has a blank name")

    val dir = payload.direction
    if (dir != null && Direction.fromWire(dir) == null) {
        return CreateDecodeResult.Failure("unknown direction: $dir")
    }

    return CreateDecodeResult.Success(payload)
}

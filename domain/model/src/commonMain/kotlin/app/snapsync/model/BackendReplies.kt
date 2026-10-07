package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * One answer from the backend, as the backend gave it — the vocabulary the `Backend` port speaks
 * (`docs/architecture.md`, "Ports are the I/O boundary named for the need").
 *
 * **It carries no verdict.** Whether a `401` means "this credential is rejected", whether a `404` means "the event
 * is gone" or "retry later", and whether a `426` means "update the app" are decisions, and decisions live in the
 * services above the port. The port only says what came back: a body it could read ([Ok]), a status and a body
 * that were not success ([Refused]), a success whose body this build cannot read ([Malformed]), or nothing at all
 * ([Unreachable]).
 *
 * [Malformed] is its own case rather than folded into [Unreachable] because the two have different remedies: a
 * transport failure heals on a retry, a shape this build does not understand never does (`docs/architecture.md`,
 * "Absence is never silent").
 */
sealed interface Reply<out T> {

    /** The backend served the call and its body read as [value]. */
    data class Ok<out T>(val value: T) : Reply<T>

    /** The backend answered, with a status that is not success. [body] is its text, verbatim. */
    data class Refused(val status: Int, val body: String) : Reply<Nothing>

    /** The backend answered success with a body this build could not read. */
    data class Malformed(val detail: String) : Reply<Nothing>

    /** No answer: the request did not complete. */
    class Unreachable(val cause: Throwable) : Reply<Nothing> {
        override fun toString(): String = "Unreachable(${cause.message ?: cause::class.simpleName})"
    }
}

/** The value of an [Reply.Ok], or `null` for every other answer. */
fun <T> Reply<T>.okOrNull(): T? = (this as? Reply.Ok)?.value

/** This reply as a [Result]: [Reply.Ok] succeeds, every other answer fails naming what came back. */
fun <T> Reply<T>.toResult(what: String): Result<T> = when (this) {
    is Reply.Ok -> Result.success(value)
    is Reply.Refused -> Result.failure(IllegalStateException("$what: HTTP $status $body"))
    is Reply.Malformed -> Result.failure(IllegalStateException("$what: malformed body — $detail"))
    is Reply.Unreachable -> Result.failure(cause)
}

/** `POST /events` — the host's chosen name and window. [endsAt] absent is the backend's legacy `+30d` fallback. */
data class CreateEventRequest(
    val name: String,
    val startsAt: String,
    val endsAt: String?,
    /**
     * The host's IANA time zone (`Europe/Berlin`), which only the event page reads to name the dates as the host chose
     * them (capability `event-site`). Optional: absent, the page shows them in UTC.
     */
    val zone: String? = null,
    /** An ENCRYPTED event's key id (16 lowercase hex); `null` creates a plain event. The key never leaves the device. */
    val keyId: String? = null,
)

/** What `POST /events` answered: the minted id, and the name the backend stored when it echoed one. */
data class EventCreated(val eventId: String, val name: String?)

/**
 * What `GET /events/<id>` answered, every field as the backend sent it — absent where it sent none. Whether the
 * absence of any field makes the answer unusable is the reader's decision, not the wire's.
 */
data class EventMeta(
    val eventId: String?,
    val name: String?,
    val createdAt: String?,
    val startsAt: String?,
    val endsAt: String?,
    val deletesAt: String?,
    /** When the event CLOSED (capability `event-lifetime`); `null` while open, and from a backend predating it. */
    val closedAt: String? = null,
    /** When the event COMPLETED — its photos deleted, its record kept until [deletesAt]. */
    val completedAt: String? = null,
    /** The event's active members, and how many of them have settled what they share. */
    val members: MemberCounts? = null,
    /** An ENCRYPTED event's key id; `null` for a plain event, and from a backend predating encryption. */
    val keyId: String? = null,
)

/**
 * An event's active members and how many of them have settled what they share (capability `sync-status`,
 * the ended event's waiting line).
 */
@Serializable
data class MemberCounts(val active: Int, val settled: Int) {
    /** How many active members the event is still waiting for. */
    val waitingFor: Int get() = (active - settled).coerceAtLeast(0)
}

/** What `PATCH /events/<id>` answered: the stored name, when the echo carried one. */
data class EventRenamed(val name: String?)

/** One resource a device has stored (`GET /files/devices/<id>`), in the terms the backend addresses it by. */
data class DeviceFile(val assetId: AssetId, val role: ResourceRole, val filename: String)

/** One resource of a foreign asset in the event-wide union (`GET /events/<id>/files`). */
class UnionResource(
    val key: String,
    val url: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
)

/** One **complete** asset in the event-wide union, tagged with its owning device and capture date. */
class UnionAsset(
    val deviceId: String,
    val assetId: AssetId,
    val creationDate: String,
    val resources: List<UnionResource>,
)

/**
 * One answer of the event-wide union (decision record `changes/incremental-union`, D3): its [assets] — all of them for a
 * full read, only those gained after the cursor it was asked from for a delta — and the [cursor] it covers, which the
 * next delta starts from. The cursor is the backend's; the app stores it and sends it back, and reads nothing into it
 * beyond its order.
 */
class UnionPage(val assets: List<UnionAsset>, val cursor: Long)

/**
 * Why the app reads the union (decision record `changes/incremental-union`, D5–D6) — what it tells the backend, which
 * records it (capability `privacy-security`, "The service records who reads an event's photo list"), and whether the
 * read is [full]. A push and a background wake read only what is new; every other reason reads everything, because
 * someone waits on the answer or a decision rests on it.
 */
enum class UnionTrigger(val wire: String, val full: Boolean) {
    /** A silent push announced a photo. */
    PUSH("push", full = false),

    /** A background wake with no push behind it. */
    WAKE("wake", full = false),

    /** The app came to the foreground: the member is looking, and a full read heals anything a delta missed. */
    FOREGROUND("foreground", full = true),

    /** Joining an event. */
    JOIN("join", full = true),

    /** Photo access became usable: the received photos already in the library are recognised against all of it. */
    GRANT("grant", full = true),

    /** A settings change began receiving, with no position for a membership that never received. */
    RECONFIGURE("reconfigure", full = true),

    /** Whether a closed event has given this device everything — the one decision where doubt must keep it. */
    LEAVE_CHECK("leave-check", full = true),
}

/** The response header naming the position a union answer covers. */
const val UNION_CURSOR_HEADER: String = "SnapSync-Cursor"

/** The request header naming why the app reads the union. */
const val UNION_TRIGGER_HEADER: String = "SnapSync-Trigger"

/**
 * `POST /attest/token` — a fresh attestation of [keyId] over [challenge], in [format]: the backend verifies it by the
 * verifier the format names. For [ProofFormat.ANDROID_KEY], [attestation] is the certificate chain, leaf first, as
 * concatenated DER (each certificate is self-delimiting), and [keyId] is this device's own name for the key — the
 * backend reads nothing from it.
 */
class MintRequest(
    val deviceId: String,
    val keyId: String,
    val format: ProofFormat,
    val attestation: ByteArray,
    val challenge: String,
)

/** `POST /attest/renew` — an assertion by the key this device attested, over [challenge]. */
class RenewRequest(val deviceId: String, val assertion: ByteArray, val challenge: String)

/**
 * What the device's integrity service produced over a challenge (the `DeviceIntegrity` port): the [handle] of the
 * key that produced it — newly created for a first proof, the one passed in for a renewal — the [format] the
 * platform's proofs have, and the [bytes] the backend verifies. [chain] summarises the certificates a fresh proof
 * presents, where the platform exposes them (Android); `null` for a renewal, for App Attest and for the fakes.
 */
class Proof(val handle: String, val format: ProofFormat, val bytes: ByteArray, val chain: AttestationChain? = null)

/**
 * The certificates a fresh proof presented, summarised for the operator (capability `privacy-security`: what a report the
 * app offered for a refused phone carries) — enough to see which root a chain ends at: names kept verbatim (a remotely
 * provisioned certificate is named after its serial, and that is not redacted), but no certificate itself, and not
 * the leaf, which is the app's own key.
 *
 * [certificates] are those ABOVE the leaf, in the chain's order (the root last); [rootKeySha256] is the lowercase hex
 * SHA-256 of the root's encoded public key — what the backend pins a root by.
 */
data class AttestationChain(val certificates: List<CertificateFacts>, val rootKeySha256: String)

/** One certificate, as [AttestationChain] summarises it: names as RFC 2253, validity as ISO instants, key e.g. `EC 256`. */
data class CertificateFacts(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val key: String,
)

/**
 * Which kind of proof a platform produces — the verifier the backend's mint dispatches to (`proof.format` on the wire).
 * A renewal names none: the backend verifies it by what the device's attestation proved.
 */
enum class ProofFormat {
    /** App Attest: an attestation object when fresh, an assertion when renewing. */
    APP_ATTEST,

    /** Android Keystore key attestation: the key's certificate chain when fresh, an ECDSA signature when renewing. */
    ANDROID_KEY,
}

/**
 * A backend refusal of this build (capability `app-update-required`): the cell the version gate publishes and the
 * status host reduces into the update screen. [minimumVersion] is the version the backend named, or `null` when the
 * refusal carried none — which is still a refusal, never the same as no refusal at all.
 */
data class VersionRefusal(val minimumVersion: String?)

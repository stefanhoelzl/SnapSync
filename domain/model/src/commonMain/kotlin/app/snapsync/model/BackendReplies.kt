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
data class CreateEventRequest(val name: String, val startsAt: String, val endsAt: String?)

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
 * platform's proofs have, and the [bytes] the backend verifies.
 */
class Proof(val handle: String, val format: ProofFormat, val bytes: ByteArray)

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

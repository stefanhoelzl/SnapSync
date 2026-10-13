package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * An event's present members, how many of them have settled what they share, and — [waiting] — how many are still
 * sharing: not settled, or with a declared photo that has not arrived. The member count and the ended event's waiting
 * line. [waiting] is `null` from a backend, or a record, that predates it.
 */
@Serializable
data class MemberCounts(val active: Int, val settled: Int, val waiting: Int? = null) {
    /** How many present members the event is still waiting for to finish sharing. */
    val waitingFor: Int get() = waiting ?: (active - settled).coerceAtLeast(0)
}

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
class UnionPage(
    val assets: List<UnionAsset>,
    val cursor: Long,
    /**
     * The event's declared total the read answered (change `count-declared-photos`): every asset the whole union
     * serves plus what members still in the event declared and have not finished — `null` from a backend that
     * predates it.
     */
    val declared: Long? = null,
)

/**
 * Why the app reads the union (decision record `changes/incremental-union`, D5–D6) — what it tells the backend, which
 * records it, and whether the read is [full]. A push and a background wake read only what is new; every other reason
 * reads everything, because someone waits on the answer or a decision rests on it.
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

    /** Whether a leaving member had everything — a doubt is a no. */
    LEAVE_CHECK("leave-check", full = true),

    /** Up to which position this device holds every photo of the others — what it reports once the event has ended. */
    RECEIVED_CHECK("received-check", full = true),
}

/**
 * What the device's integrity service produced over a challenge (the `DeviceIntegrity` port): the [handle] of the
 * key that produced it — newly created for a first proof, the one passed in for a renewal — the [format] the
 * platform's proofs have, and the [bytes] the backend verifies. [chain] summarises the certificates a fresh proof
 * presents, where the platform exposes them (Android); `null` for a renewal, for App Attest and for the fakes.
 */
class Proof(val handle: String, val format: ProofFormat, val bytes: ByteArray, val chain: AttestationChain? = null)

/**
 * The certificates a fresh proof presented, summarised for the operator (what a report the app offered for a refused
 * phone carries) — enough to see which root a chain ends at: names kept verbatim (a remotely provisioned certificate is
 * named after its serial, and that is not redacted), but no certificate itself, and not the leaf, which is the app's
 * own key.
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
 * A backend refusal of this build: the cell the version gate publishes and the status host reduces into the update
 * screen. [minimumVersion] is the version the backend named, or `null` when the refusal carried none — which is still a
 * refusal, never the same as no refusal at all.
 */
data class VersionRefusal(val minimumVersion: String?)

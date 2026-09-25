package app.snapsync.attest

import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.AttestClient
import app.snapsync.model.TokenOutcome

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Production [AttestClient] over an injected Ktor [HttpClient] and host (Darwin on iOS).
 *
 * These are the only three routes reachable **without** a token — they are what issues it. Each is
 * self-authenticating: the challenge is HMAC-signed and stateless, and token/renew carry an attestation or
 * an assertion the backend verifies before minting anything.
 *
 * No failure throws: this runs on background wakes, where a thrown error would take down work that has nothing
 * to do with attestation. [challenge] answers `null` for any failure; [mintToken] and [renewToken] classify theirs
 * into a [TokenOutcome] ([tokenRefusal]), because the caller answers a stale challenge, a missing record, a
 * refusal and no answer at all differently — and none of them by dropping the token it holds.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `AttestClientContract` holds [challenge] and every REFUSAL against the
 * real backend (`LiveEdgeContractsTest`): a forged attestation, a challenge the edge never issued, and a renewal
 * for a device that never attested each answer a non-[TokenOutcome.Minted] outcome. A SUCCESSFUL [mintToken] or
 * [renewToken] has no host: the backend verifies a genuine App Attest attestation (or an assertion by a key it
 * attested) over a challenge it issued within the last 300 s, against a certificate chain valid at request time.
 * Only an entitled app on a physical device produces one, a recording of it is dead five minutes later, and the
 * local rig deliberately fakes ENROLMENT (`api/src/dev/fallback.ts`), never attestation. So those beliefs live
 * here: a genuine attestation or assertion answers a fresh token ([TokenOutcome.Minted]); a refused one answers
 * `401` ([TokenOutcome.Refused]; `not attested` → [TokenOutcome.NotAttested]); a stale challenge answers `409`
 * under v2 and `401 stale challenge` under the frozen v1 (both [TokenOutcome.ChallengeStale]); a malformed body
 * answers `400`. The mapping is pinned by `HttpAttestClientTest`, and the edge's verification of a real Apple
 * attestation by `api/test/attest.test.ts`.
 */
@OptIn(ExperimentalEncodingApi::class)
class HttpAttestClient(
    private val client: HttpClient,
    host: String,
) : AttestClient {

    private val base = host.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun challenge(): String? = runCatchingCancellable {
        val res = client.get("$base/attest/challenge")
        if (!res.status.isSuccess()) return null
        json.parseToJsonElement(res.bodyAsText()).jsonObject["challenge"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun mintToken(
        deviceId: String,
        keyId: String,
        attestation: ByteArray,
        challenge: String,
    ): TokenOutcome = post(
        "$base/attest/token",
        JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(deviceId),
                "keyId" to JsonPrimitive(keyId),
                "attestation" to JsonPrimitive(Base64.encode(attestation)),
                "challenge" to JsonPrimitive(challenge),
            ),
        ),
    )

    override suspend fun renewToken(
        deviceId: String,
        assertion: ByteArray,
        challenge: String,
    ): TokenOutcome = post(
        "$base/attest/renew",
        JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(deviceId),
                "assertion" to JsonPrimitive(Base64.encode(assertion)),
                "challenge" to JsonPrimitive(challenge),
            ),
        ),
    )

    private suspend fun post(url: String, body: JsonObject): TokenOutcome = runCatchingCancellable {
        val res = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = res.bodyAsText()
        if (res.status.isSuccess()) {
            json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.content
                ?.let { TokenOutcome.Minted(it) } ?: TokenOutcome.Unreachable
        } else {
            tokenRefusal(res.status.value, text)
        }
    }.getOrElse { TokenOutcome.Unreachable }
}

/**
 * A refused `/attest/token` or `/attest/renew`, classified by its status and the backend's plain-text body — the
 * route's own vocabulary, read only here (decision record `harden-seam-bug-classes`, D10).
 *
 * `409` is v2's stale challenge. Under v1, which is frozen, a stale challenge is still a `401` whose body says so,
 * and it is classified the same way, because the remedy is the same: one fresh challenge. `401 not attested` is the
 * renewal's "no record on file". Any other `4xx` is a verdict on what was sent; a `5xx` is no verdict at all.
 */
internal fun tokenRefusal(status: Int, body: String): TokenOutcome = when {
    status == 409 || (status == 401 && body.trim() == "stale challenge") -> TokenOutcome.ChallengeStale
    status == 401 && body.trim() == "not attested" -> TokenOutcome.NotAttested
    status in 400..499 -> TokenOutcome.Refused
    else -> TokenOutcome.Unreachable
}

package app.snapsync.world

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import app.snapsync.model.runCatchingCancellable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The three ungated `/attest/…` routes, or null when this request is not one of them (capability
 * `privacy-security`).
 *
 * The mini-edge verifies nothing cryptographic, but it mints only for what the real edge would accept in its place:
 * an attestation of the shape the in-memory `DeviceIntegrity` produces for its key (`attestation:<keyId>:<challenge>`),
 * over a challenge THIS edge issued. So the Backend contract's refusal clauses hold here as they do on the real
 * edge, and a world that attests (`World.attests`) obtains a well-formed token — `<deviceId>.<expiry>.<signature>`,
 * because the device reads its own expiry out of it — whose signature is the challenge it was minted over, so each
 * mint is a DIFFERENT token, as each of the real edge's is (a recovery that re-minted the rejected token would be
 * indistinguishable from none).
 *
 * Renewal answers `401 not attested`, always: the faithful default is a backend holding no enrolment for this
 * device (after a restore, or once the sweep collected it), which sends the device down a full attestation — the
 * recovery path, rather than the cheap one no contract host can reach.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun MockRequestHandleScope.attestRoute(
    store: BackendStore,
    json: Json,
    method: HttpMethod,
    segments: List<String>,
    body: String,
): HttpResponseData? {
    if (segments.size != 2 || segments[0] != "attest") return null
    return when {
        method == HttpMethod.Get && segments[1] == "challenge" ->
            respond("""{"challenge":"${store.issueChallenge()}"}""", HttpStatusCode.OK, jsonHeaders)
        method == HttpMethod.Post && segments[1] == "token" -> {
            val fields = json.parseToJsonElement(body).jsonObject
            fun field(name: String) = fields[name]?.jsonPrimitive?.content.orEmpty()
            val challenge = field("challenge")
            val keyId = field("keyId")
            val attestation = runCatchingCancellable { Base64.decode(field("attestation")).decodeToString() }.getOrNull()
            if (store.issued(challenge) && attestation == "attestation:$keyId:$challenge") {
                val token = "${field("deviceId")}.$TOKEN_EXPIRES_AT_EPOCH_SECONDS.$challenge"
                respond("""{"token":"$token"}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("attestation rejected", HttpStatusCode.Unauthorized)
            }
        }
        method == HttpMethod.Post && segments[1] == "renew" -> respond("not attested", HttpStatusCode.Unauthorized)
        else -> null
    }
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** The minted token's expiry, in epoch seconds: 90 days, which outlives the world's clock pinned at the epoch. */
private const val TOKEN_EXPIRES_AT_EPOCH_SECONDS: Long = 90L * 24 * 60 * 60

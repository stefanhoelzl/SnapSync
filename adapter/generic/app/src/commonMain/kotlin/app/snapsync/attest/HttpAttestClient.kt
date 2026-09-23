package app.snapsync.attest

import app.snapsync.ports.AttestClient

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
 * Every failure maps to `null` (never an exception): this runs on background wakes, where a thrown error
 * would take down work that has nothing to do with attestation. A null simply leaves the old token in
 * place, and the next wake tries again.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `AttestClientContract` holds only [challenge] to the real backend
 * (`LiveEdgeContractsTest`). [mintToken] and [renewToken] have no host CI runs: the backend verifies an App
 * Attest attestation (or an assertion over a key it attested) before minting, only an entitled app on a
 * physical device can produce one — `DCAppAttestService.isSupported` is false on the simulator, and a JVM has
 * no Secure Enclave — and the local rig deliberately fakes ENROLMENT (`api/src/dev/fallback.ts`), never
 * attestation. A clause for them would be reachable only by `InMemoryAttestClient`, which `port-contracts`
 * refuses ("Every clause runs against a real implementation on some host"). So their beliefs live here: a
 * refused attestation or assertion answers `401`, which maps to `null`; a stale challenge answers `401` too;
 * a malformed body answers `400`. Their mapping is pinned by `HttpAttestClientTest`.
 */
@OptIn(ExperimentalEncodingApi::class)
class HttpAttestClient(
    private val client: HttpClient,
    host: String,
) : AttestClient {

    private val base = host.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun challenge(): String? = runCatching {
        val res = client.get("$base/attest/challenge")
        if (!res.status.isSuccess()) return null
        json.parseToJsonElement(res.bodyAsText()).jsonObject["challenge"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun mintToken(
        deviceId: String,
        keyId: String,
        attestation: ByteArray,
        challenge: String,
    ): String? = post(
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
    ): String? = post(
        "$base/attest/renew",
        JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(deviceId),
                "assertion" to JsonPrimitive(Base64.encode(assertion)),
                "challenge" to JsonPrimitive(challenge),
            ),
        ),
    )

    private suspend fun post(url: String, body: JsonObject): String? = runCatching {
        val res = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!res.status.isSuccess()) return null
        json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]?.jsonPrimitive?.content
    }.getOrNull()
}

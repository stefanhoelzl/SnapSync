@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package app.snapsync.attest

import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.objcBoundary
import app.snapsync.objc.objcCallback
import app.snapsync.model.Proof
import app.snapsync.model.ProofFormat
import app.snapsync.ports.DeviceIntegrity
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.posix.memcpy


/**
 * The real [DeviceIntegrity], over Apple's `DCAppAttestService`: a fresh proof is `generateKey` then `attestKey`
 * (the throttled path, once per key); a proof by an existing handle is `generateAssertion` (local, no network).
 *
 * `platform.DeviceCheck` is a Kotlin/Native platform klib, so this needs no cinterop `.def` and no Swift
 * shim — the whole ceremony is reachable straight from `iosMain`.
 *
 * **[isAvailable] returns false inside the upload extension.** That was measured on device, not inferred:
 * the app process reported `isSupported=true` and completed the full ceremony (a 5712-byte attestation, a
 * 141-byte assertion), while the extension — in the very same build, in a healthy `process()` cycle that
 * uploaded a photo one second later — reported `false`. So the extension can never attest or renew, and
 * every renewal in this capability happens in the app.
 *
 * Errors are surfaced as exceptions and caught by [DeviceAttestation], which reduces them to "no fresh
 * token" rather than letting them escape into a background wake.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceIntegrity internal constructor(
    /** Where the App Attest calls go: the real service, or — in a contract run — a recording. */
    private val service: AppAttestApi,
) : DeviceIntegrity {

    constructor() : this(SystemAppAttestApi)

    private val log = Logger.withTag("deviceIntegrity")

    override fun isAvailable(): Boolean = service.isSupported()

    // No availability check of its own: an unavailable service refuses through the platform's own error, so the
    // call sequence the device recording holds is exactly what the adapter hands App Attest.
    override suspend fun prove(challenge: String, handle: String?): Proof =
        if (handle == null) {
            val keyId = generateKey()
            Proof(keyId, ProofFormat.APP_ATTEST, attest(keyId, challenge))
        } else {
            Proof(handle, ProofFormat.APP_ATTEST, assert(handle, challenge))
        }

    // Each completion is what Objective-C calls (through SystemAppAttestApi): contained, like every such block, and a
    // refusal resumes the caller with the platform's error.
    private suspend fun generateKey(): String = objcCallback(log, "generateKey.completion") { done ->
        service.generateKey { keyId, error ->
            objcBoundary(done) { keyId ?: throw attestError("generateKey", error) }
        }
    }

    private suspend fun attest(keyId: String, challenge: String): ByteArray =
        objcCallback(log, "attestKey.completion") { done ->
            service.attestKey(keyId, sha256(challenge)) { data, error ->
                objcBoundary(done) { data?.toByteArray() ?: throw attestError("attestKey", error) }
            }
        }

    private suspend fun assert(keyId: String, challenge: String): ByteArray =
        objcCallback(log, "generateAssertion.completion") { done ->
            service.generateAssertion(keyId, sha256(challenge)) { data, error ->
                objcBoundary(done) { data?.toByteArray() ?: throw attestError("generateAssertion", error) }
            }
        }

    private fun attestError(step: String, error: NSError?): ObjCFailure = ObjCFailure.of("App Attest $step", error)

    /** Apple wants the SHA-256 of the client data; the challenge IS our client data. */
    private fun sha256(value: String): NSData = memScoped {
        val input = value.encodeToByteArray()
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { out ->
            CC_SHA256(allocArrayOf(input), input.size.toUInt(), out.addressOf(0))
        }
        digest.toByteArray().toNSData()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isEmpty()) return out
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

package app.snapsync.attest

import app.snapsync.ports.AttestKey

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The real [AttestKey], over Apple's `DCAppAttestService`.
 *
 * `platform.DeviceCheck` is a Kotlin/Native platform klib, so this needs no cinterop `.def` and no Swift
 * shim — the whole ceremony is reachable straight from `iosMain`.
 *
 * **[isSupported] returns false inside the upload extension.** That was measured on device, not inferred:
 * the app process reported `isSupported=true` and completed the full ceremony (a 5712-byte attestation, a
 * 141-byte assertion), while the extension — in the very same build, in a healthy `process()` cycle that
 * uploaded a photo one second later — reported `false`. So the extension can never attest or renew, and
 * every renewal in this capability happens in the app.
 *
 * Errors are surfaced as exceptions and caught by [DeviceAttestation], which reduces them to "no fresh
 * token" rather than letting them escape into a background wake.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAttestKey(
    private val service: DCAppAttestService = DCAppAttestService.sharedService,
) : AttestKey {

    override fun isSupported(): Boolean = service.isSupported()

    override suspend fun generateKey(): String = suspendCoroutine { cont ->
        service.generateKeyWithCompletionHandler { keyId, error ->
            if (keyId != null) cont.resume(keyId) else cont.resumeWithException(attestError("generateKey", error))
        }
    }

    override suspend fun attest(keyId: String, challenge: String): ByteArray =
        suspendCoroutine { cont ->
            service.attestKey(keyId, sha256(challenge)) { data, error ->
                val bytes = data?.toByteArray()
                if (bytes != null) cont.resume(bytes) else cont.resumeWithException(attestError("attestKey", error))
            }
        }

    override suspend fun assert(keyId: String, challenge: String): ByteArray =
        suspendCoroutine { cont ->
            service.generateAssertion(keyId, sha256(challenge)) { data, error ->
                val bytes = data?.toByteArray()
                if (bytes != null) {
                    cont.resume(bytes)
                } else {
                    cont.resumeWithException(attestError("generateAssertion", error))
                }
            }
        }

    private fun attestError(step: String, error: NSError?): IllegalStateException =
        IllegalStateException(
            "App Attest $step failed: domain=${error?.domain} code=${error?.code} ${error?.localizedDescription}",
        )

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

@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package app.snapsync.attest

import app.snapsync.model.Proof
import app.snapsync.model.ProofFormat
import app.snapsync.objc.objcBoundary
import app.snapsync.objc.objcCallback
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.ProveResult
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
 * Only the app process composes one. App Attest is unavailable in the upload extension — measured on device: the app
 * process completed the full ceremony (a 5712-byte attestation, a 141-byte assertion) while the extension, in the very
 * same build, reported `isSupported=false` — and on every simulator.
 *
 * **Every refusal is a value** ([ProveResult.Refused]): App Attest answers each call with a value or an `NSError`, and
 * [refusal] reads it — `invalidKey` is [ProveResult.Refused.UnknownKey]; `featureUnsupported`, or any refusal in a
 * process whose service is not supported, [ProveResult.Refused.NotAvailable] (a simulator does not answer
 * `featureUnsupported` — measured in CI, so the platform's own `isSupported` decides); anything else
 * [ProveResult.Refused.Failed]. `isSupported` is asked only after a refusal that is not `invalidKey`, so the ceremony
 * and an unknown key ask App Attest exactly what the device recording holds.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceIntegrity internal constructor(
    /** Where the App Attest calls go: the real service, or — in a contract run — a recording. */
    private val service: AppAttestApi,
) : DeviceIntegrity {

    constructor() : this(SystemAppAttestApi)

    private val log = Logger.withTag("deviceIntegrity")

    override suspend fun prove(challenge: String, handle: String?): ProveResult =
        if (handle == null) {
            when (val key = generateKey()) {
                is Step.Refused -> key.refused
                is Step.Got -> when (val attested = attest(key.value, challenge)) {
                    is Step.Refused -> attested.refused
                    is Step.Got -> ProveResult.Ok(Proof(key.value, ProofFormat.APP_ATTEST, attested.value))
                }
            }
        } else {
            when (val asserted = assert(handle, challenge)) {
                is Step.Refused -> asserted.refused
                is Step.Got -> ProveResult.Ok(Proof(handle, ProofFormat.APP_ATTEST, asserted.value))
            }
        }

    /** What one App Attest call answered: its value, or the refusal it amounts to. */
    private sealed interface Step<out T> {
        class Got<T>(val value: T) : Step<T>

        class Refused(val refused: ProveResult.Refused) : Step<Nothing>
    }

    // Each completion is what Objective-C calls (through SystemAppAttestApi): contained, like every such block, and it
    // answers its caller with a value either way.
    private suspend fun generateKey(): Step<String> = objcCallback(log, "generateKey.completion") { done ->
        service.generateKey { keyId, error ->
            objcBoundary(done) { keyId?.let { Step.Got(it) } ?: Step.Refused(refused("generateKey", error)) }
        }
    }

    private suspend fun attest(keyId: String, challenge: String): Step<ByteArray> =
        objcCallback(log, "attestKey.completion") { done ->
            service.attestKey(keyId, sha256(challenge)) { data, error ->
                objcBoundary(
                    done,
                ) { data?.let { Step.Got(it.toByteArray()) } ?: Step.Refused(refused("attestKey", error)) }
            }
        }

    private suspend fun assert(keyId: String, challenge: String): Step<ByteArray> =
        objcCallback(log, "generateAssertion.completion") { done ->
            service.generateAssertion(keyId, sha256(challenge)) { data, error ->
                objcBoundary(done) {
                    data?.let { Step.Got(it.toByteArray()) } ?: Step.Refused(refused("generateAssertion", error))
                }
            }
        }

    /** What App Attest's [error] for [step] amounts to; a call that answered neither value nor error is refused too. */
    private fun refused(step: String, error: NSError?): ProveResult.Refused {
        val reason = "App Attest $step: domain=${error?.domain} code=${error?.code} ${error?.localizedDescription}"
        val code = deviceCheckCode(error)
        return when {
            code == INVALID_KEY -> ProveResult.Refused.UnknownKey(reason)
            code == FEATURE_UNSUPPORTED || !service.isSupported() -> ProveResult.Refused.NotAvailable(reason)
            else -> ProveResult.Refused.Failed(reason)
        }
    }

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

/** The `DCError` codes a refusal is told apart by (`DCError.h`). */
private const val DEVICE_CHECK_DOMAIN = "com.apple.devicecheck.error"
private const val FEATURE_UNSUPPORTED = 1L
private const val INVALID_KEY = 3L

/** The `DCError` [code] of [error], or `null` when it is not App Attest's own. */
private fun deviceCheckCode(error: NSError?): Long? = error?.takeIf { it.domain == DEVICE_CHECK_DOMAIN }?.code

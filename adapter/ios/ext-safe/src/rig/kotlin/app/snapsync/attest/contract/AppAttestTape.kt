@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.attest.contract

import app.snapsync.attest.AppAttestApi
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.create

/*
 * App Attest's operating-system boundary as TEXT (capability `port-contracts`, "Hosts CI cannot reach are
 * recorded at the operating-system boundary and replayed on every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into
 * `iosTest` otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call
 * differently.
 *
 * WHAT IS MASKED, AND WHY (capability `port-contracts`, "Replay matches exactly, in order, over deterministic
 * clauses"). Recordings are committed to a public repository, so App Attest's credential material never enters
 * one: an attestation carries Apple's certificate chain and a receipt for this device's key, and an assertion is
 * a signature by it. Both are recorded as [MASKED]. A `keyId` the service minted is masked too, in the answer
 * AND in every later request that carries it — so on replay the adapter is handed the placeholder, passes it
 * back, and the request still matches. A `keyId` the clause invented (an unknown key) is deterministic and kept.
 *
 * What stays in the clear is what the adapter decides: the `clientDataHash` it hands the service — the SHA-256
 * of the challenge, which the edge's verifier recomputes — and how each error answer reads.
 */

internal const val MASKED = "<masked>"

/** The placeholder bytes a masked attestation or assertion replays as: non-empty, and obviously not Apple's. */
private val maskedBytes = MASKED.encodeToByteArray()

/** Remembers which `keyId`s the service minted in this clause, so requests can mask exactly those. */
internal class MintedKeys {
    private val minted = mutableSetOf<String>()

    fun mint(keyId: String): String {
        minted += keyId
        return MASKED
    }

    fun render(keyId: String): String = if (keyId in minted || keyId == MASKED) MASKED else keyId
}

private fun NSData.hex(): String {
    val bytes = this.bytes?.reinterpret<kotlinx.cinterop.UByteVar>() ?: return ""
    return (0 until length.toInt()).joinToString("") { bytes[it].toString(16).padStart(2, '0') }
}

private fun renderError(error: NSError?): String =
    "error domain=${error?.domain} code=${error?.code} description=" +
        (error?.localizedDescription ?: "").replace('\n', ' ')

private fun parseError(answer: String): NSError {
    val domain = answer.substringAfter("domain=").substringBefore(' ')
    val code = answer.substringAfter("code=").substringBefore(' ').toLong()
    val description = answer.substringAfter("description=")
    return NSError.errorWithDomain(domain, code, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description))
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun attestCall(symbol: String, keyId: String, clientDataHash: NSData, keys: MintedKeys) =
    "$symbol(keyId=${keys.render(keyId)} clientDataHash=${clientDataHash.hex()})"

/** Passes every call to [real] and records it, with the answer, in the clause block [recorder] has open. */
internal class RecordingAppAttestApi(private val real: AppAttestApi, private val recorder: Recorder) : AppAttestApi {
    private val keys = MintedKeys()

    override fun isSupported(): Boolean = real.isSupported().also { recorder.record("isSupported()", "$it") }

    override fun generateKey(completion: (String?, NSError?) -> Unit) = real.generateKey { keyId, error ->
        recorder.record("generateKey()", if (keyId != null) "keyId=${keys.mint(keyId)}" else renderError(error))
        completion(keyId, error)
    }

    override fun attestKey(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) {
        val call = attestCall("attestKey", keyId, clientDataHash, keys)
        real.attestKey(keyId, clientDataHash) { data, error ->
            recorder.record(call, if (data != null) "data=$MASKED" else renderError(error))
            completion(data, error)
        }
    }

    override fun generateAssertion(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) {
        val call = attestCall("generateAssertion", keyId, clientDataHash, keys)
        real.generateAssertion(keyId, clientDataHash) { data, error ->
            recorder.record(call, if (data != null) "data=$MASKED" else renderError(error))
            completion(data, error)
        }
    }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingAppAttestApi(private val replayer: Replayer) : AppAttestApi {
    private val keys = MintedKeys()

    override fun isSupported(): Boolean = replayer.answer("isSupported()").toBooleanStrict()

    override fun generateKey(completion: (String?, NSError?) -> Unit) {
        val answer = replayer.answer("generateKey()")
        if (answer.startsWith("keyId=")) completion(keys.mint(answer.removePrefix("keyId=")), null) else completion(null, parseError(answer))
    }

    override fun attestKey(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        answerData(replayer.answer(attestCall("attestKey", keyId, clientDataHash, keys)), completion)

    override fun generateAssertion(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        answerData(replayer.answer(attestCall("generateAssertion", keyId, clientDataHash, keys)), completion)

    private fun answerData(answer: String, completion: (NSData?, NSError?) -> Unit) =
        if (answer.startsWith("data=")) completion(maskedBytes.toNSData(), null) else completion(null, parseError(answer))
}

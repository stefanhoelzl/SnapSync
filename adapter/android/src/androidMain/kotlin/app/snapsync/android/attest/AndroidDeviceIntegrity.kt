package app.snapsync.android.attest

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.snapsync.model.Proof
import app.snapsync.model.ProofFormat
import app.snapsync.ports.DeviceIntegrity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * The Android [DeviceIntegrity]: Android Keystore **key attestation**, the counterpart of App Attest
 * (`api/src/android-attest.ts` is its verifier).
 *
 * - **A fresh proof** creates an EC P-256 signing key in the Keystore whose attestation challenge is the SHA-256 of the
 *   server's challenge — the port's challenge crosses as a string and this adapter hashes it, as the iOS one does — and
 *   answers the key's certificate chain, leaf first, as concatenated DER ([ProofFormat.ANDROID_KEY]). The handle is
 *   the key's Keystore alias, new per attestation: the backend reads nothing from it.
 * - **A fresh proof also carries a summary of that chain** ([summarise]) for the operator — never sent to the backend.
 * - **A renewal** signs the challenge's UTF-8 bytes with that key (SHA256withECDSA, DER) — local work, no network.
 *
 * The key never leaves the Keystore and needs no user authentication, so any wake may renew. Which hardware holds it
 * (TEE, or none on an emulator — the chain then ends at the AOSP software root) is the attestation's to report and the
 * backend's to judge; this adapter asks for no particular one. A key of an attestation the backend refused, or of one
 * superseded by a re-attestation, stays in the Keystore: nothing names it again, and the count is bounded by how often
 * the device attests.
 *
 * Refusals are exceptions (the port's contract): an unknown handle, or a Keystore that cannot generate or sign.
 */
class AndroidDeviceIntegrity : DeviceIntegrity {

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    // Key attestation exists on every Android this app runs on (API 24+; minSdk is 30), in the one process there is.
    override fun isAvailable(): Boolean = true

    override suspend fun prove(challenge: String, handle: String?): Proof =
        if (handle == null) attest(challenge) else Proof(handle, ProofFormat.ANDROID_KEY, sign(handle, challenge))

    private fun attest(challenge: String): Proof {
        val alias = "$ALIAS_PREFIX${UUID.randomUUID()}"
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(sha256(challenge))
                    .build(),
            )
        }.generateKeyPair()
        val chain = keyStore().getCertificateChain(alias)
        check(!chain.isNullOrEmpty()) { "the Keystore attested no certificate chain for $alias" }
        // The summary rides beside the bytes, for a report the user may send if the backend refuses them (capability
        // `privacy-security`); the backend never sees it.
        val der = chain.fold(ByteArray(0)) { all, cert -> all + cert.encoded }
        return Proof(alias, ProofFormat.ANDROID_KEY, der, chain = summarise(chain))
    }

    private fun sign(alias: String, challenge: String): ByteArray {
        val key = keyStore().getKey(alias, null) as? PrivateKey
            ?: error("the Keystore holds no attested key '$alias'")
        return Signature.getInstance(SIGNATURE).apply {
            initSign(key)
            update(challenge.encodeToByteArray())
        }.sign()
    }

    private fun sha256(challenge: String): ByteArray = MessageDigest.getInstance(
        "SHA-256",
    ).digest(challenge.encodeToByteArray())

    companion object {
        /** Every attested key's alias starts so — runtime identity, beside the secure store's own key. */
        const val ALIAS_PREFIX = "app.snapsync.attest."

        private const val KEYSTORE = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
        private const val SIGNATURE = "SHA256withECDSA"
    }
}

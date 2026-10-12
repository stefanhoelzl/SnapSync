package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.AesKey
import app.snapsync.model.GcmNonce
import app.snapsync.ports.Crypto
import app.snapsync.ports.DeviceIntegrity

/** [Crypto] as its clause's [CallLog] sees it. */
fun Crypto.recorded(log: CallLog): Crypto = CryptoProxy(this, log)

internal class CryptoProxy(private val inner: Crypto, log: CallLog) : Crypto {
    private val r = log.recorder("Crypto")

    override fun randomBytes(count: Int) = r.returns("randomBytes", inner.randomBytes(count))
    override fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ) = r.returns("hmacSha256", inner.hmacSha256(key, message))
    override fun sha256(chunks: Sequence<ByteArray>) = r.returns("sha256", inner.sha256(chunks))
    override fun aesGcmSeal(key: AesKey, nonce: GcmNonce, plaintext: ByteArray) =
        r.returns("aesGcmSeal", inner.aesGcmSeal(key, nonce, plaintext))
    override fun aesGcmOpen(key: AesKey, nonce: GcmNonce, sealed: ByteArray) =
        r.returns("aesGcmOpen", inner.aesGcmOpen(key, nonce, sealed))
}

/** [DeviceIntegrity] as its clause's [CallLog] sees it. */
fun DeviceIntegrity.recorded(log: CallLog): DeviceIntegrity = DeviceIntegrityProxy(this, log)

internal class DeviceIntegrityProxy(private val inner: DeviceIntegrity, log: CallLog) : DeviceIntegrity {
    private val r = log.recorder("DeviceIntegrity")

    override suspend fun prove(challenge: String, handle: String?) = r.answer("prove", inner.prove(challenge, handle))
}

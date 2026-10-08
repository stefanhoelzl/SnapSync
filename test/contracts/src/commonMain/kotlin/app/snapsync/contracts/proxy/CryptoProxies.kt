package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.ports.Crypto
import app.snapsync.ports.DeviceIntegrity
import kotlin.reflect.KClass

/** [Crypto] as its clause's [CallLog] sees it. */
fun Crypto.recorded(log: CallLog): Crypto = CryptoProxy(this, log)

internal class CryptoProxy(private val inner: Crypto, log: CallLog) : Crypto {
    private val r = log.recorder("Crypto")

    override fun randomBytes(count: Int) = r.returns("randomBytes", inner.randomBytes(count))
    override fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ) = r.returns("hmacSha256", inner.hmacSha256(key, message))
    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray) =
        r.returns(
            "aesGcmSeal",
            r.throwing("aesGcmSeal", THROWS.getValue("aesGcmSeal")) { inner.aesGcmSeal(key, nonce, plaintext) },
        )
    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray) =
        r.returns(
            "aesGcmOpen",
            r.throwing("aesGcmOpen", THROWS.getValue("aesGcmOpen")) { inner.aesGcmOpen(key, nonce, sealed) },
        )

    companion object {
        /** Each member's `@Throws`, which Kotlin/Native cannot read at run time; held to the port by `:test:architecture`. */
        val THROWS: Map<String, KClass<out Throwable>> = mapOf(
            "aesGcmSeal" to IllegalArgumentException::class,
            "aesGcmOpen" to IllegalArgumentException::class,
        )
    }
}

/** [DeviceIntegrity] as its clause's [CallLog] sees it. */
fun DeviceIntegrity.recorded(log: CallLog): DeviceIntegrity = DeviceIntegrityProxy(this, log)

internal class DeviceIntegrityProxy(private val inner: DeviceIntegrity, log: CallLog) : DeviceIntegrity {
    private val r = log.recorder("DeviceIntegrity")

    override fun isAvailable() = r.answer("isAvailable", inner.isAvailable())
    override suspend fun prove(challenge: String, handle: String?) =
        r.returns("prove", r.throwing("prove", THROWS.getValue("prove")) { inner.prove(challenge, handle) })

    companion object {
        /** Each member's `@Throws`, which Kotlin/Native cannot read at run time; held to the port by `:test:architecture`. */
        val THROWS: Map<String, KClass<out Throwable>> = mapOf("prove" to Exception::class)
    }
}

package app.snapsync.ports

import app.snapsync.model.AesKey
import app.snapsync.model.GcmNonce

/**
 * **The platform's cryptographic primitives** — one external system, and nothing decided here: which key, which nonce,
 * how a file is cut into segments and what it is bound to are the encrypted file format's (`:domain:model`'s
 * `EncryptedFileFormat`) and the services' business. Each adapter answers with its platform's own implementation —
 * CryptoKit and CommonCrypto on iOS, the platform's JCA provider on Android and the JVM — so no cryptography is
 * written here, only framed.
 *
 * Every member is synchronous and thread-agnostic: a segment is 64 KiB, which the slowest supported phone seals in
 * well under a millisecond. None throws: a key and a nonce are of their exact length by construction ([AesKey],
 * [GcmNonce]), and a seal that does not authenticate opens to `null`.
 *
 * Its promises are the port contract `CryptoContract`: published known-answer vectors for HMAC-SHA256 (RFC 4231) and
 * AES-256-GCM, a tampered seal that never opens, and random bytes that are not repeated.
 */
interface Crypto : Port {

    /** [count] (≥ 0) bytes from the platform's cryptographically secure generator. */
    fun randomBytes(count: Int): ByteArray

    /** HMAC-SHA256 of [message] under [key] — 32 bytes. Any key length, the empty key included (RFC 2104). */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

    /** AES-256-GCM of [plaintext] under [key] and [nonce], no associated data: the ciphertext then its 16-byte tag. */
    fun aesGcmSeal(key: AesKey, nonce: GcmNonce, plaintext: ByteArray): ByteArray

    /**
     * The plaintext [sealed] (ciphertext then tag) was sealed from under [key] and [nonce], or `null` when it does
     * not authenticate — a wrong key, a wrong nonce, any changed byte, or fewer bytes than a tag.
     */
    fun aesGcmOpen(key: AesKey, nonce: GcmNonce, sealed: ByteArray): ByteArray?
}

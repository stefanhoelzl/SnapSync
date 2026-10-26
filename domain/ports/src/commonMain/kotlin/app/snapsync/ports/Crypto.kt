package app.snapsync.ports

/**
 * **The platform's cryptographic primitives** — one external system, and nothing decided here: which key, which nonce,
 * how a file is cut into segments and what it is bound to are the encrypted file format's (`:domain:format`'s
 * `EncryptedFileFormat`) and the data layer's business. Each adapter answers with its platform's own implementation —
 * CryptoKit and CommonCrypto on iOS, the platform's JCA provider on Android and the JVM — so no cryptography is
 * written here, only framed.
 *
 * Every member is synchronous and thread-agnostic: a segment is 64 KiB, which the slowest supported phone seals in
 * well under a millisecond. None throws: a key and a nonce are handed over at their exact lengths ([AesKey],
 * [GcmNonce] — the data layer's cipher builds them from the format's), and a seal that does not authenticate opens to
 * `null`.
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

    /**
     * SHA-256 of [chunks] concatenated, in order — 32 bytes. A sequence, so a file is hashed as it is read rather than
     * held whole: what a resumable upload declares its bytes by (`docs/architecture.md`, "Resumable uploads").
     */
    fun sha256(chunks: Sequence<ByteArray>): ByteArray
}

/**
 * An AES-256 key — 32 bytes, as the encrypted file format derives it. Its builder makes it of that length: a platform
 * cipher handed 16 or 24 bytes would quietly run AES-128 or AES-192.
 */
class AesKey(val bytes: ByteArray) {
    override fun toString(): String = "AesKey(***)"
}

/** An AES-GCM nonce — 12 bytes, as the encrypted file format builds it. */
class GcmNonce(val bytes: ByteArray)

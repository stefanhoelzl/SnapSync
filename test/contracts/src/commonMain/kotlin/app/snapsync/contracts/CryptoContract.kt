package app.snapsync.contracts

import app.snapsync.ports.Crypto
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The primitives keep no state, so there is one: the platform's own implementation, ready. */
enum class CryptoState {
    READY,
}

/**
 * What `Crypto` promises (`docs/architecture.md`; the port's KDoc carries why). Every answer is a published
 * known-answer vector — HMAC-SHA256 from RFC 4231, AES-256-GCM from the GCM specification's test cases 13–15 — so a
 * platform that passes computes exactly what every other one does, and what the encrypted file format's reference
 * vectors were made with.
 */
object CryptoContract : Contract<CryptoState, Crypto>("Crypto") {

    private fun hex(s: String): ByteArray = ByteArray(
        s.length / 2,
    ) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val GCM_KEY = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val GCM_NONCE = hex("cafebabefacedbaddecaf888")
    private val GCM_PLAIN = hex(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
    )
    private val GCM_SEALED = hex(
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
            "b094dac5d93471bdec1a502270e3cc6c",
    )

    override val clauses = clauses {

        clause(
            "HMAC_SHA256_IS_RFC_4231",
            CryptoState.READY,
            covers = cells { on<Crypto>().answers(Crypto::hmacSha256).returns() },
        ) { crypto ->
            assertContentEquals(
                hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
                crypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()),
                "test case 1",
            )
            assertContentEquals(
                hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
                crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()),
                "test case 2",
            )
            assertContentEquals(
                hex("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
                crypto.hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ),
                "test case 6: a key longer than the block is hashed first",
            )
            assertEquals(32, crypto.hmacSha256(ByteArray(32), ByteArray(0)).size, "an empty message still MACs")
        }

        clause(
            "AES_256_GCM_IS_THE_SPECIFICATIONS_KNOWN_ANSWERS",
            CryptoState.READY,
            covers = cells {
                on<Crypto> {
                    answers(Crypto::aesGcmSeal).returns()
                    answers(Crypto::aesGcmOpen).returns()
                }
            },
        ) { crypto ->
            assertContentEquals(
                hex("530f8afbc74536b9a963b4f1c4cb738b"),
                crypto.aesGcmSeal(ByteArray(32), ByteArray(12), ByteArray(0)),
                "test case 13: nothing sealed is the tag alone",
            )
            assertContentEquals(
                hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"),
                crypto.aesGcmSeal(ByteArray(32), ByteArray(12), ByteArray(16)),
                "test case 14",
            )
            assertContentEquals(GCM_SEALED, crypto.aesGcmSeal(GCM_KEY, GCM_NONCE, GCM_PLAIN), "test case 15")
            assertContentEquals(GCM_PLAIN, crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, GCM_SEALED), "and it opens")
            assertContentEquals(
                ByteArray(0),
                crypto.aesGcmOpen(ByteArray(32), ByteArray(12), hex("530f8afbc74536b9a963b4f1c4cb738b")),
            )
        }

        clause(
            "A_CHANGED_SEAL_NEVER_OPENS",
            CryptoState.READY,
            covers = cells { on<Crypto>().answers(Crypto::aesGcmOpen).with(null) },
        ) { crypto ->
            fun flipped(at: Int) = GCM_SEALED.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, flipped(0)), "a changed ciphertext byte")
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, flipped(GCM_SEALED.size - 1)), "a changed tag byte")
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, GCM_SEALED.copyOf(GCM_SEALED.size - 1)), "a cut seal")
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE.copyOf().also { it[11] = 0 }, GCM_SEALED), "another nonce")
            assertNull(crypto.aesGcmOpen(ByteArray(32), GCM_NONCE, GCM_SEALED), "another key")
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, ByteArray(15)), "shorter than a tag")
        }

        clause(
            "A_KEY_THAT_IS_NOT_AES_256_IS_REFUSED",
            CryptoState.READY,
            covers = cells {
                on<Crypto> {
                    answers(Crypto::aesGcmSeal).throws()
                    answers(Crypto::aesGcmOpen).throws()
                }
            },
        ) { crypto ->
            assertFailsWith<IllegalArgumentException>("a 16-byte key would seal AES-128") {
                crypto.aesGcmSeal(ByteArray(16), GCM_NONCE, GCM_PLAIN)
            }
            assertFailsWith<IllegalArgumentException>("nor does one open") {
                crypto.aesGcmOpen(ByteArray(33), GCM_NONCE, GCM_SEALED)
            }
        }

        clause(
            "RANDOM_BYTES_ARE_FRESH",
            CryptoState.READY,
            covers = cells { on<Crypto>().answers(Crypto::randomBytes).returns() },
        ) { crypto ->
            assertEquals(0, crypto.randomBytes(0).size)
            assertEquals(7, crypto.randomBytes(7).size)
            val draws = List(8) { crypto.randomBytes(32).toList() }
            assertEquals(draws.size, draws.toSet().size, "no 32-byte draw repeats")
            assertFalse(draws.any { draw -> draw.all { it == 0.toByte() } }, "and none is all zeros")
        }
    }
}

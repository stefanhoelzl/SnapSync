package app.snapsync.contracts

import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.GcmNonce
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The primitives keep no state; what differs is whose bytes they produce. */
enum class CryptoState {
    /** The platform's own implementation of the standard algorithms: its answers are the published vectors. */
    READY,

    /**
     * Any implementation, a stand-in whose bytes are its own included: only what every implementation keeps is asked of
     * it — a seal opens under its own key and nonce, a changed one never does, a MAC is keyed and repeatable, random
     * bytes are fresh.
     */
    OWN_BYTES,
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

    private fun key(bytes: ByteArray): AesKey = AesKey(
        bytes.also { assertEquals(32, it.size, "a vector key is 32 bytes") },
    )
    private fun nonce(bytes: ByteArray): GcmNonce = GcmNonce(
        bytes.also { assertEquals(12, it.size, "a vector nonce is 12 bytes") },
    )

    private val ZERO_KEY get() = key(ByteArray(32))
    private val ZERO_NONCE get() = nonce(ByteArray(12))
    private val GCM_KEY get() = key(hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"))
    private val GCM_NONCE_BYTES = hex("cafebabefacedbaddecaf888")
    private val GCM_NONCE get() = nonce(GCM_NONCE_BYTES)
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
            "AN_EMPTY_KEY_MACS_AS_RFC_2104_SAYS",
            CryptoState.READY,
            covers = cells { on<Crypto>().answers(Crypto::hmacSha256).returns() },
        ) { crypto ->
            // RFC 2104 pads a key with zeros to the block, so the empty key is a key like any other — and JCA's
            // `SecretKeySpec` refuses it, which is what this pins on every platform.
            assertContentEquals(
                hex("b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad"),
                crypto.hmacSha256(ByteArray(0), ByteArray(0)),
                "the empty key over the empty message",
            )
            assertContentEquals(
                hex("76d9e7194e7dbc3aa00bbe8ffb9f6fcb5a932170f971f948bb2ab61607d2b9d6"),
                crypto.hmacSha256(ByteArray(0), "what do ya want for nothing?".encodeToByteArray()),
                "the empty key over a message",
            )
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
                crypto.aesGcmSeal(ZERO_KEY, ZERO_NONCE, ByteArray(0)),
                "test case 13: nothing sealed is the tag alone",
            )
            assertContentEquals(
                hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"),
                crypto.aesGcmSeal(ZERO_KEY, ZERO_NONCE, ByteArray(16)),
                "test case 14",
            )
            assertContentEquals(GCM_SEALED, crypto.aesGcmSeal(GCM_KEY, GCM_NONCE, GCM_PLAIN), "test case 15")
            assertContentEquals(GCM_PLAIN, crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, GCM_SEALED), "and it opens")
            assertContentEquals(
                ByteArray(0),
                crypto.aesGcmOpen(ZERO_KEY, ZERO_NONCE, hex("530f8afbc74536b9a963b4f1c4cb738b")),
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
            assertNull(
                crypto.aesGcmOpen(GCM_KEY, nonce(GCM_NONCE_BYTES.copyOf().also { it[11] = 0 }), GCM_SEALED),
                "another nonce",
            )
            assertNull(crypto.aesGcmOpen(ZERO_KEY, GCM_NONCE, GCM_SEALED), "another key")
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, ByteArray(15)), "shorter than a tag")
        }

        clause(
            "SHA256_IS_FIPS_180_4_OVER_THE_CHUNKS_CONCATENATED",
            CryptoState.READY,
            covers = cells { on<Crypto>().answers(Crypto::sha256).returns() },
        ) { crypto ->
            val abc = hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            assertContentEquals(
                hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                crypto.sha256(emptySequence()),
                "nothing at all",
            )
            assertContentEquals(abc, crypto.sha256(sequenceOf("abc".encodeToByteArray())), "\"abc\"")
            assertContentEquals(
                abc,
                crypto.sha256(sequenceOf("a", "", "bc").map { it.encodeToByteArray() }),
                "cut anywhere, an empty chunk included, it hashes the same bytes",
            )
            assertContentEquals(
                hex("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
                crypto.sha256(generateSequence { ByteArray(1000) { 'a'.code.toByte() } }.take(1000)),
                "a million 'a's, a thousand chunks",
            )
        }

        clause(
            "OWN_BYTES_A_SEAL_OPENS_UNDER_ITS_KEY_AND_A_CHANGED_ONE_NEVER_DOES",
            CryptoState.OWN_BYTES,
            covers = cells {
                on<Crypto> {
                    answers(Crypto::aesGcmSeal).returns()
                    answers(Crypto::aesGcmOpen).returns()
                    answers(Crypto::aesGcmOpen).with(null)
                }
            },
        ) { crypto ->
            val sealed = crypto.aesGcmSeal(GCM_KEY, GCM_NONCE, GCM_PLAIN)
            assertContentEquals(GCM_PLAIN, crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, sealed), "it opens to what was sealed")
            val changed = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNull(crypto.aesGcmOpen(GCM_KEY, GCM_NONCE, changed), "a changed seal never opens")
            assertNull(crypto.aesGcmOpen(ZERO_KEY, GCM_NONCE, sealed), "nor under another key")
        }

        clause(
            "OWN_BYTES_A_MAC_IS_KEYED_AND_REPEATABLE",
            CryptoState.OWN_BYTES,
            covers = cells { on<Crypto>().answers(Crypto::hmacSha256).returns() },
        ) { crypto ->
            val macKey = ByteArray(32) { it.toByte() }
            val mac = crypto.hmacSha256(macKey, GCM_PLAIN)
            assertEquals(32, mac.size, "an HMAC-SHA256 is 32 bytes")
            assertContentEquals(mac, crypto.hmacSha256(macKey, GCM_PLAIN), "the same key and message, the same MAC")
            assertFalse(mac.contentEquals(crypto.hmacSha256(ByteArray(32), GCM_PLAIN)), "another key, another MAC")
        }

        clause(
            "OWN_BYTES_A_DIGEST_IS_OF_THE_BYTES_HOWEVER_CHUNKED",
            CryptoState.OWN_BYTES,
            covers = cells { on<Crypto>().answers(Crypto::sha256).returns() },
        ) { crypto ->
            val digest = crypto.sha256(sequenceOf(GCM_PLAIN))
            assertEquals(32, digest.size, "a SHA-256 is 32 bytes")
            val cut = sequenceOf(GCM_PLAIN.copyOf(5), ByteArray(0), GCM_PLAIN.copyOfRange(5, GCM_PLAIN.size))
            assertContentEquals(digest, crypto.sha256(cut), "cut anywhere, an empty chunk included, the same digest")
            val other = crypto.sha256(sequenceOf(GCM_PLAIN.copyOf(5)))
            assertFalse(digest.contentEquals(other), "other bytes, another one")
        }

        clause(
            "OWN_BYTES_RANDOM_BYTES_ARE_FRESH",
            CryptoState.OWN_BYTES,
            covers = cells { on<Crypto>().answers(Crypto::randomBytes).returns() },
        ) { crypto ->
            assertEquals(7, crypto.randomBytes(7).size)
            val draws = List(8) { crypto.randomBytes(32).toList() }
            assertEquals(draws.size, draws.toSet().size, "no 32-byte draw repeats")
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

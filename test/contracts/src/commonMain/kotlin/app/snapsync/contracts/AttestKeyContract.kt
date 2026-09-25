package app.snapsync.contracts

import app.snapsync.ports.AttestKey
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The states an [AttestKey] can be found in, as far as a clause cares. */
enum class AttestKeyState {
    /**
     * App Attest does not exist in this process: a simulator, or the upload extension on a device (measured —
     * the extension is not a bound host, so that belief stays in `IosAttestKey`'s documentation).
     */
    UNSUPPORTED,

    /** The app process on a device: the Secure Enclave and Apple's attestation service are there. */
    SUPPORTED,
}

/**
 * What App Attest's device half promises (`docs/architecture.md` — this list IS the port's specification).
 *
 * The point of the refusal clauses is that a refusal is an **exception**, never a hang and never an invented
 * answer: `DeviceAttestation` reduces an exception to "no fresh token", and it cannot reduce a coroutine that
 * never resumes. The ceremony clause holds what the app needs from a supported service — a key that attests
 * once and then asserts cheaply. Whether Apple's bytes VERIFY is the edge's business, proven against a real
 * Apple fixture in `api/test/attest.test.ts`; a device recording masks them (they are a credential).
 *
 * Every input is deterministic — the challenge and the unknown key derive from the clause id — so a device
 * recording replays against the same calls in CI.
 */
object AttestKeyContract : Contract<AttestKeyState, AttestKey>("AttestKey") {

    fun challenge(clauseId: String) = "challenge:$clauseId"

    private fun unknownKey(clauseId: String) = "unknown-key:$clauseId"

    override val clauses = clauses {

        clause("AN_UNSUPPORTED_SERVICE_SAYS_SO", AttestKeyState.UNSUPPORTED) { key ->
            assertFalse(key.isSupported(), "a process without App Attest must not claim it")
        }

        clause("AN_UNSUPPORTED_SERVICE_REFUSES_TO_GENERATE", AttestKeyState.UNSUPPORTED) { key ->
            val failure = assertFailsWith<Exception> { key.generateKey() }
            assertTrue(!failure.message.isNullOrBlank(), "a refusal carries a diagnosis")
        }

        clause("AN_UNSUPPORTED_SERVICE_REFUSES_TO_ATTEST", AttestKeyState.UNSUPPORTED) { key ->
            val id = "AN_UNSUPPORTED_SERVICE_REFUSES_TO_ATTEST"
            assertFailsWith<Exception> { key.attest(unknownKey(id), challenge(id)) }
        }

        clause("AN_UNSUPPORTED_SERVICE_REFUSES_TO_ASSERT", AttestKeyState.UNSUPPORTED) { key ->
            val id = "AN_UNSUPPORTED_SERVICE_REFUSES_TO_ASSERT"
            assertFailsWith<Exception> { key.assert(unknownKey(id), challenge(id)) }
        }

        clause("A_SUPPORTED_SERVICE_SAYS_SO", AttestKeyState.SUPPORTED) { key ->
            assertTrue(key.isSupported())
        }

        clause("A_GENERATED_KEY_ATTESTS_THEN_ASSERTS", AttestKeyState.SUPPORTED) { key ->
            val challenge = challenge("A_GENERATED_KEY_ATTESTS_THEN_ASSERTS")
            val keyId = key.generateKey()
            assertTrue(keyId.isNotBlank(), "a generated key has an id")
            assertTrue(key.attest(keyId, challenge).isNotEmpty(), "an attestation is bytes")
            assertTrue(key.assert(keyId, challenge).isNotEmpty(), "an assertion by the attested key is bytes")
        }

        clause("AN_UNKNOWN_KEY_CANNOT_ATTEST", AttestKeyState.SUPPORTED) { key ->
            val id = "AN_UNKNOWN_KEY_CANNOT_ATTEST"
            assertFailsWith<Exception> { key.attest(unknownKey(id), challenge(id)) }
        }

        clause("AN_UNKNOWN_KEY_CANNOT_ASSERT", AttestKeyState.SUPPORTED) { key ->
            val id = "AN_UNKNOWN_KEY_CANNOT_ASSERT"
            assertFailsWith<Exception> { key.assert(unknownKey(id), challenge(id)) }
        }
    }
}

package app.snapsync.contracts

import app.snapsync.ports.DeviceIntegrity
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The states a [DeviceIntegrity] can be found in, as far as a clause cares. */
enum class DeviceIntegrityState {
    /**
     * No integrity service in this process: a simulator, or the upload extension on a device (measured — the
     * extension is not a bound host, so that belief stays in `IosDeviceIntegrity`'s documentation).
     */
    UNAVAILABLE,

    /** The app process on a device: the Secure Enclave and Apple's attestation service are there. */
    AVAILABLE,
}

/**
 * What the device's integrity service promises (`docs/testing.md` — this list IS the port's specification).
 *
 * The point of the refusal clauses is that a refusal is an **exception**, never a hang and never an invented proof:
 * `DeviceAttestation` reduces an exception to "no fresh token", and it cannot reduce a coroutine that never
 * resumes. The ceremony clause holds what the app needs from an available service — a fresh proof that names a new
 * key, which then signs cheaply. Whether Apple's bytes VERIFY is the backend's business, proven against a real Apple
 * fixture in `api/test/attest.test.ts`; a device recording masks them (they are a credential).
 *
 * Every input is deterministic — the challenge and the unknown handle derive from the clause id — so a device
 * recording replays against the same calls in CI.
 */
object DeviceIntegrityContract : Contract<DeviceIntegrityState, DeviceIntegrity>("DeviceIntegrity") {

    fun challenge(clauseId: String) = "challenge:$clauseId"

    private fun unknownHandle(clauseId: String) = "unknown-key:$clauseId"

    override val clauses = clauses {

        clause("AN_UNAVAILABLE_SERVICE_SAYS_SO", DeviceIntegrityState.UNAVAILABLE) { integrity ->
            assertFalse(integrity.isAvailable(), "a process without App Attest must not claim it")
        }

        clause("AN_UNAVAILABLE_SERVICE_REFUSES_A_FRESH_PROOF", DeviceIntegrityState.UNAVAILABLE) { integrity ->
            val failure = assertFailsWith<Exception> { integrity.prove(challenge("AN_UNAVAILABLE_SERVICE_REFUSES_A_FRESH_PROOF")) }
            assertTrue(!failure.message.isNullOrBlank(), "a refusal carries a diagnosis")
        }

        clause("AN_UNAVAILABLE_SERVICE_REFUSES_A_RENEWAL_PROOF", DeviceIntegrityState.UNAVAILABLE) { integrity ->
            val id = "AN_UNAVAILABLE_SERVICE_REFUSES_A_RENEWAL_PROOF"
            assertFailsWith<Exception> { integrity.prove(challenge(id), unknownHandle(id)) }
        }

        clause("AN_AVAILABLE_SERVICE_SAYS_SO", DeviceIntegrityState.AVAILABLE) { integrity ->
            assertTrue(integrity.isAvailable())
        }

        clause("A_FRESH_PROOF_NAMES_A_KEY_THAT_THEN_SIGNS", DeviceIntegrityState.AVAILABLE) { integrity ->
            val challenge = challenge("A_FRESH_PROOF_NAMES_A_KEY_THAT_THEN_SIGNS")
            val fresh = integrity.prove(challenge)
            assertTrue(fresh.handle.isNotBlank(), "a fresh proof names the key it created")
            assertTrue(fresh.bytes.isNotEmpty(), "an attestation is bytes")
            val renewal = integrity.prove(challenge, fresh.handle)
            assertEquals(fresh.handle, renewal.handle, "a renewal proof is by the key it was asked for")
            assertTrue(renewal.bytes.isNotEmpty(), "an assertion by the attested key is bytes")
        }

        clause("AN_UNKNOWN_KEY_CANNOT_SIGN", DeviceIntegrityState.AVAILABLE) { integrity ->
            val id = "AN_UNKNOWN_KEY_CANNOT_SIGN"
            assertFailsWith<Exception> { integrity.prove(challenge(id), unknownHandle(id)) }
        }
    }
}

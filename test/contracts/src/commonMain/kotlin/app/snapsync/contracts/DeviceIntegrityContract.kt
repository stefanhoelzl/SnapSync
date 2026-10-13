package app.snapsync.contracts

import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.ProveResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The states a [DeviceIntegrity] can be found in, as far as a clause cares. */
enum class DeviceIntegrityState {
    /** No integrity service in this process: a simulator. */
    UNAVAILABLE,

    /** The app process on a device: the Secure Enclave (or Keystore) and the vendor's attestation service are there. */
    AVAILABLE,

    /**
     * An available service holding a key under [DeviceIntegrityContract.unusableHandle] that it refuses to sign with —
     * entered by the binding, through the platform, before the clause runs.
     */
    UNUSABLE_KEY,

    /**
     * An available service whose vendor cannot be reached — the device offline — on a platform whose fresh attestation
     * is the vendor's to issue (Apple's App Attest server).
     */
    VENDOR_UNREACHABLE,
}

/**
 * What the device's integrity service promises (`docs/testing.md` — this list IS the port's specification).
 *
 * The point of the refusal clauses is that a refusal is a **value**, never a hang, never a throw and never an invented
 * proof: `DeviceAttestation` reduces any [ProveResult.Refused] to "no fresh token", and it cannot reduce a coroutine
 * that never resumes. The leaves tell a missing service, a handle naming no key and any other refusal apart, for the
 * log. The ceremony clause holds what the app needs from an available service — a fresh proof that names a new key,
 * which then signs cheaply. Whether the vendor's bytes VERIFY is the backend's business, proven against a real Apple
 * fixture in `api/test/attest.test.ts`; a device recording masks them (they are a credential).
 *
 * Every input is deterministic — the challenge and the handles derive from the clause id — so a device recording
 * replays against the same calls in CI.
 */
object DeviceIntegrityContract : Contract<DeviceIntegrityState, DeviceIntegrity>("DeviceIntegrity") {

    fun challenge(clauseId: String) = "challenge:$clauseId"

    private fun unknownHandle(clauseId: String) = "unknown-key:$clauseId"

    /** The handle a binding entering [DeviceIntegrityState.UNUSABLE_KEY] holds its unusable key under. */
    fun unusableHandle(clauseId: String) = "unusable-key:$clauseId"

    override val clauses = clauses {

        clause(
            "AN_UNAVAILABLE_SERVICE_REFUSES_A_FRESH_PROOF",
            DeviceIntegrityState.UNAVAILABLE,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Refused.NotAvailable::class)
            },
        ) { integrity ->
            val refused = assertIs<ProveResult.Refused.NotAvailable>(
                integrity.prove(challenge("AN_UNAVAILABLE_SERVICE_REFUSES_A_FRESH_PROOF")),
            )
            assertTrue(refused.reason.isNotBlank(), "a refusal carries a diagnosis")
        }

        clause(
            "AN_UNAVAILABLE_SERVICE_REFUSES_A_RENEWAL_PROOF",
            DeviceIntegrityState.UNAVAILABLE,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Refused.NotAvailable::class)
            },
        ) { integrity ->
            val id = "AN_UNAVAILABLE_SERVICE_REFUSES_A_RENEWAL_PROOF"
            assertIs<ProveResult.Refused.NotAvailable>(integrity.prove(challenge(id), unknownHandle(id)))
        }

        clause(
            "A_FRESH_PROOF_NAMES_A_KEY_THAT_THEN_SIGNS",
            DeviceIntegrityState.AVAILABLE,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Ok::class)
            },
        ) { integrity ->
            val challenge = challenge("A_FRESH_PROOF_NAMES_A_KEY_THAT_THEN_SIGNS")
            val fresh = assertIs<ProveResult.Ok>(integrity.prove(challenge)).proof
            assertTrue(fresh.handle.isNotBlank(), "a fresh proof names the key it created")
            assertTrue(fresh.bytes.isNotEmpty(), "an attestation is bytes")
            val renewal = assertIs<ProveResult.Ok>(integrity.prove(challenge, fresh.handle)).proof
            assertEquals(fresh.handle, renewal.handle, "a renewal proof is by the key it was asked for")
            assertTrue(renewal.bytes.isNotEmpty(), "an assertion by the attested key is bytes")
        }

        clause(
            "AN_UNKNOWN_KEY_CANNOT_SIGN",
            DeviceIntegrityState.AVAILABLE,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Refused.UnknownKey::class)
            },
        ) { integrity ->
            val id = "AN_UNKNOWN_KEY_CANNOT_SIGN"
            assertIs<ProveResult.Refused.UnknownKey>(integrity.prove(challenge(id), unknownHandle(id)))
        }

        clause(
            "A_KEY_THE_PLATFORM_WILL_NOT_USE_IS_REFUSED",
            DeviceIntegrityState.UNUSABLE_KEY,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Refused.Failed::class)
            },
        ) { integrity ->
            val id = "A_KEY_THE_PLATFORM_WILL_NOT_USE_IS_REFUSED"
            // A key that is there but refused is not an unknown one: the platform's own refusal, carried for the log.
            val refused = assertIs<ProveResult.Refused.Failed>(integrity.prove(challenge(id), unusableHandle(id)))
            assertTrue(refused.reason.isNotBlank(), "a refusal carries a diagnosis")
        }

        clause(
            "VENDOR_UNREACHABLE_A_FRESH_PROOF_IS_REFUSED",
            DeviceIntegrityState.VENDOR_UNREACHABLE,
            covers = cells {
                on<DeviceIntegrity>().answers(DeviceIntegrity::prove).with(ProveResult.Refused.Failed::class)
            },
        ) { integrity ->
            // The service is there and the key is new; only the vendor that vouches for it cannot be asked.
            val refused = assertIs<ProveResult.Refused.Failed>(integrity.prove(challenge("VENDOR_UNREACHABLE"), null))
            assertTrue(refused.reason.isNotBlank(), "a refusal carries a diagnosis")
        }
    }
}

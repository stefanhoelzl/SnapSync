package app.snapsync.integration

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL device-side push registration (`PushRegistration` + the push-token service over `HttpBackend`, installed by the host
 * assembly exactly as the iOS shell installs it) driven through the control protocol against the mini-edge,
 * asserting the backend outcome: the device config document (`backend/device-config`). The backend's own
 * promise — a token is published, a rotated one overwrites — is `BackendContract`'s `DEVICE_CONFIG_*` clauses; the backend
 * notify fan-out is Deno-side logic, covered by `api/test/app.test.ts`. What stays here is the wiring: which
 * triggers make the composed app register at all.
 */
class PushRegistrationIntegrationTest {

    private val REGISTERED = """{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}"""

    /** How many registrations the backend stored for this device — the config is last-write-wins, the count is not. */
    private suspend fun Rig.registrations(): Int =
        deviceJson("backend/device-config").getValue("writes").jsonPrimitive.content.toInt()

    /** The config document the backend holds for this device, or null when none was registered. */
    private suspend fun Rig.deviceConfig(): String? =
        deviceJson("backend/device-config")["config"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /**
     * A JOIN re-registers the push token (capability `receiving-photos`): committing a join runs the real
     * `flow/Provision`, whose `registerPush` re-PUTs the delivered token — closing the warm-rejoin window the
     * nightly sweep's device-record collection opens (capability `event-lifetime`).
     *
     * The config is last-write-wins, so the document cannot tell one registration from two; the backend's count of
     * stored registrations can. The delivery registers once; the join registers again.
     */
    @Test
    fun the_join_flow_re_registers_the_push_token() = rigTest {
        create(name = "Trip")
        os("app", "onPushToken", "DEADBEEF")
        eventually<Int>(read = { registrations() }) { it == 1 }

        join()

        eventually<Int>(read = { registrations() }) { it == 2 }
        assertEquals(REGISTERED, deviceConfig())
        assertTrue(state().ready.configResolved, "joined")
    }

    /**
     * **The credential arm of `AppCore.installPushRegistration` is wired** (capabilities
     * `receiving-photos`, `privacy-security`).
     *
     * THE JOIN THIS PINS. The app publishes a delivered APNs token only when it differs from the last
     * registration the backend accepted. A registration refused because the backend rejected the credential
     * would therefore wait for the next app entry to be re-sent, and a device that receives no silent pushes
     * gets few — no download wakes, and none of the wake-driven attestation renewals that depend on them. What saves it is that obtaining a NEW
     * credential re-runs the registration, and that is a join between two features that are blind to each
     * other: the trust feature announces the new token, the push feature consumes the announcement.
     *
     * RESHAPED to isolate the arm by what the backend holds. The first registration — the DELIVERY arm — is
     * refused (`backend/refuse-credential`, armed before the token arrives). Since the authenticated backend retries
     * a call refused for its token once, with the token its recovery obtained (phase 11c), that refused PUT is itself
     * answered by its retry: ONE stored registration. Obtaining the new credential also announces it, and the
     * credential arm re-publishes unconditionally: a SECOND stored registration, with no second delivery — which only
     * the credential announcement can have made. (That second PUT is redundant here, the accepted cost of keeping the
     * healing path unconditional: it is what still heals a refusal the retry could not, as in the upload extension.)
     *
     * That the first PUT is the one refused rests on it being the only credentialed request between the lever and the
     * delivery on an unjoined host — verified in this test's console output (`PUT …/devices/… → 401`, then its
     * retry `→ 201`).
     */
    @Test
    fun a_new_credential_re_registers_the_push_token_with_no_new_delivery() = rigTest {
        // The foreground's wake-point refresh gives the device a credential to be refused. Obtained before the
        // token arrives, so this first credential is not the change the test watches for.
        os("app", "onForeground")
        device("backend/refuse-credential")

        // The OS delivers a token: registration #1, through the DELIVERY arm — refused.
        os("app", "onPushToken", "DEADBEEF")

        // The refused delivery's retry stores one; the credential arm, with no second delivery, stores the other.
        eventually<Int>(read = { registrations() }) { it == 2 }
        assertEquals(REGISTERED, deviceConfig())
    }
}

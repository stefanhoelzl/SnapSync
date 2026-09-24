package app.snapsync.integration

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL device-side push registration (`PushRegistration` + `HttpPushTokenPublisher`, installed by the host
 * assembly exactly as the iOS shell installs it) driven through the control protocol against the mini-edge,
 * asserting the backend outcome: the device config document (`backend/device-config`). The publisher's own
 * contract — a token is published, a rotated one overwrites — is `PushTokenPublisherContract`'s; the backend
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
     * A JOIN re-registers the push token (capability `push-registration`): committing a join runs the real
     * `flow/Provision`, whose `registerPush` re-PUTs the delivered token — closing the warm-rejoin window the
     * nightly sweep's device-record collection opens (capability `scheduled-cleanup`).
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
     * `push-registration`, `device-attestation`).
     *
     * THE JOIN THIS PINS. The app publishes a delivered APNs token only when it differs from the last
     * registration the backend accepted. A registration refused because the backend rejected the credential
     * would therefore wait for the next app entry to be re-sent, and a device that receives no silent pushes
     * gets few — no download wakes, and none of the wake-driven attestation renewals that depend on them. What saves it is that obtaining a NEW
     * credential re-runs the registration, and that is a join between two features that are blind to each
     * other: the trust feature announces the new token, the push feature consumes the announcement.
     *
     * RESHAPED to isolate the arm by what the backend holds. The first registration — the DELIVERY arm — is
     * refused (`backend/refuse-credential`, armed before the token arrives), so no device config lands. The core
     * then obtains a new credential; the config appearing after that, with no second delivery, can only have come
     * through the credential announcement.
     *
     * The refusal is observable as the backend's count of stored registrations: exactly one lands, after the refusal.
     * That the first PUT is the one refused rests on it being the only
     * credentialed request between the lever and the delivery on an unjoined host — verified in this test's console
     * output (`PUT …/devices/… → 401`, then `push token registered`).
     */
    @Test
    fun a_new_credential_re_registers_the_push_token_with_no_new_delivery() = rigTest {
        // The foreground's wake-point refresh gives the device a credential to be refused. Obtained before the
        // token arrives, so this first credential is not the change the test watches for.
        os("app", "onForeground")
        device("backend/refuse-credential")

        // The OS delivers a token: registration #1, through the DELIVERY arm — refused.
        os("app", "onPushToken", "DEADBEEF")

        // Registration #2, with no second delivery: the credential arm, and nothing else, can have done it. The ONE
        // stored registration is that one — the refused delivery never reached the store.
        eventually<String?>(read = { deviceConfig() }) { it == REGISTERED }
        assertEquals(1, registrations(), "the delivery's registration was refused; only the credential arm's landed")
    }
}

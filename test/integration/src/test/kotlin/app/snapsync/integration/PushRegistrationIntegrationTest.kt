package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * The REAL device-side push registration (`PushRegistration` + the push-token service over the backend gateway, installed by the host
 * assembly exactly as the iOS shell installs it) driven through the control protocol against the backend mock,
 * asserting the backend outcome: each registration it stores. The backend's own promise — a token is published, a
 * rotated one overwrites — is `BackendContract`'s `DEVICE_CONFIG_*` clauses; the backend notify fan-out is Deno-side
 * logic, covered by `api/test/app.test.ts`. What stays here is the wiring: which triggers make the composed app register
 * at all.
 */
@Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
class PushRegistrationIntegrationTest {

    /**
     * A JOIN re-registers the push token: committing a join runs the real `flow/Provision`, whose `registerPush`
     * re-PUTs the delivered token — closing the warm-rejoin window the nightly sweep's device-record collection opens.
     *
     * The config is last-write-wins, so the document cannot tell one registration from two; the backend's record of
     * each stored registration can. The delivery registered once, before the join; the join registers again.
     */
    @Test
    fun the_join_flow_re_registers_the_push_token() = behaviour {
        given {
            createdEvent(name = "Trip")
            pushToken(TOKEN)
        }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.DeviceRegistered> { it.token == TOKEN }
        }
    }

    /**
     * **The credential arm of `AppCore.installCompositionSubscriptions` is wired**.
     *
     * THE JOIN THIS PINS. The app publishes a delivered APNs token only when it differs from the last
     * registration the backend accepted. A registration refused because the backend rejected the credential
     * would therefore wait for the next app entry to be re-sent, and a device that receives no silent pushes
     * gets few — no download wakes, and none of the wake-driven attestation renewals that depend on them. What saves it is that obtaining a NEW
     * credential re-runs the registration, and that is a join between two features that are blind to each
     * other: the trust feature announces the new token, the push feature consumes the announcement.
     *
     * RESHAPED to isolate the arm by what the backend stores. The first registration — the DELIVERY arm — is
     * refused (`refuseNextCredential`, armed before the token arrives). Since the authenticated backend retries a call
     * refused for its token once, with the token its recovery obtained (phase 11c), that refused PUT is itself answered
     * by its retry: ONE stored registration. Obtaining the new credential also announces it, and the credential arm
     * re-publishes unconditionally: a SECOND stored registration, with no second delivery — which only the credential
     * announcement can have made. (That second PUT is redundant here, the accepted cost of keeping the healing path
     * unconditional: it is what still heals a refusal the retry could not, as in the upload extension.)
     *
     * That the first PUT is the one refused rests on it being the only credentialed request between the lever and the
     * delivery on an unjoined host.
     */
    @Test
    fun a_new_credential_re_registers_the_push_token_with_no_new_delivery() = behaviour {
        given {
            // The foreground's wake-point refresh gives the device a credential to be refused. Obtained before the
            // token arrives, so this first credential is not the change the test watches for.
            inFront()
            step("the next credential refused", act = { backend.refuseNextCredential() }, until = {})
        }
        // The OS delivers a token: registration #1, through the DELIVERY arm — refused, and stored by its retry; the
        // credential arm, with no second delivery, stores the other.
        whenever { os.pushToken(TOKEN) }
        then { times(2) { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } } }
    }

    private companion object {
        const val TOKEN = "DEADBEEF"
    }
}

package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RefusalCause
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * **What the service's answers to a proof make of this phone** (decision record `changes/tell-service-faults`), read
 * off the screen and the requests the service received: a mint the service failed to answer is no verdict on the
 * phone, so a create it fails names no refusal; a renewal the service holds no record for is attested afresh, and that
 * attestation's refusal is named; and a rejected token the service cannot replace leaves the rejected request unsent,
 * for the next delivery.
 */
class TrustAnswersIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "No answer is not a refusal",
    )
    fun a_create_whose_first_proof_the_service_fails_to_mint_fails_without_a_refusal() = behaviour {
        given {
            step(
                "the service fails every mint",
                act = { backend.fail(MINT, BackendFault.SERVER_ERROR, ALWAYS) },
                until = {},
            )
            inFront()
        }

        whenever { createTheEvent() }
        then {
            screen<Layer.CreateEvent> { it.error != null }
            neverScreen<Layer.CreateEvent> { it.error in REFUSALS }
            never<WorldEvent.EventCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "No answer is not a refusal",
    )
    fun a_create_whose_proof_challenge_goes_stale_twice_fails_without_a_refusal() = behaviour {
        given {
            step("every mint's challenge stale", act = { backend.fail(MINT, BackendFault.CLOSED, ALWAYS) }, until = {})
            inFront()
        }

        whenever { createTheEvent() }
        then {
            screen<Layer.CreateEvent> { it.error != null }
            neverScreen<Layer.CreateEvent> { it.error in REFUSALS }
            never<WorldEvent.EventCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "No answer is not a refusal",
    )
    fun a_create_whose_proof_challenge_the_service_fails_to_issue_fails_without_a_refusal() = behaviour {
        given {
            step(
                "the service hands out no challenge",
                act = { backend.fail(CHALLENGE, BackendFault.SERVER_ERROR, ALWAYS) },
                until = {},
            )
            inFront()
        }

        for (fault in CHALLENGE_FAULTS) {
            whenever {
                backend.recover(CHALLENGE)
                backend.fail(CHALLENGE, fault, ALWAYS)
                createTheEvent()
            }
            then {
                event<WorldEvent.BackendReceived> { it.operation == CHALLENGE }
                screen<Layer.CreateEvent> { it.error != null }
                neverScreen<Layer.CreateEvent> { it.error in REFUSALS }
                never<WorldEvent.BackendReceived> { it.operation == MINT }
                never<WorldEvent.EventCreated>()
            }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "No answer is not a refusal",
    )
    fun a_create_whose_proof_the_service_cannot_be_reached_to_mint_fails_without_a_refusal() = behaviour {
        given {
            step(
                "no mint reaches the service",
                act = { backend.fail(MINT, BackendFault.UNREACHABLE, ALWAYS) },
                until = {},
            )
            inFront()
        }

        whenever { createTheEvent() }
        then {
            screen<Layer.CreateEvent> { it.error != null }
            neverScreen<Layer.CreateEvent> { it.error in REFUSALS }
            never<WorldEvent.EventCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
    )
    fun a_renewal_whose_challenge_the_service_fails_to_issue_names_no_refusal() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background, past the proof's life", act = {
                os.background()
                advance(PROOF_EXPIRED)
            }, until = { settled { true } })
        }

        for (fault in CHALLENGE_FAULTS) {
            whenever {
                backend.fail(CHALLENGE, fault, ALWAYS)
                os.foreground()
            }
            then {
                event<WorldEvent.BackendReceived> { it.operation == CHALLENGE }
                never<WorldEvent.BackendReceived> { it.operation == RENEW || it.operation == MINT }
                neverScreen<Layer.Joined> { (it.health as? SyncHealth.Unattested)?.refusal != null }
                settled { true }
            }
            whenever {
                backend.recover(CHALLENGE)
                os.background()
            }
            then { settled { true } }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "A refused phone's line names the cause",
    )
    fun a_renewal_the_service_holds_no_record_for_attests_afresh_and_its_refusal_is_named() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
        }

        whenever {
            backend.refuseAttestation(AttestationRefusal.DEVICE_MODIFIED)
            advance(PROOF_EXPIRED)
            os.foreground()
        }
        then {
            inOrder {
                event<WorldEvent.BackendReceived> { it.operation == RENEW }
                event<WorldEvent.BackendReceived> { it.operation == MINT }
            }
            screen<Layer.Joined> { it.health == SyncHealth.Unattested(RefusalCause.DEVICE_MODIFIED) }
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
    fun a_rejected_token_the_service_cannot_renew_leaves_the_registration_for_the_next_delivery() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("the next token refused, and every renewal failing", act = {
                backend.refuseNextCredential()
                backend.fail(RENEW, BackendFault.SERVER_ERROR, ALWAYS)
            }, until = {})
        }

        whenever { os.pushToken(TOKEN) }
        then {
            never<WorldEvent.DeviceRegistered>()
            settled { true }
        }

        whenever {
            backend.recover()
            os.pushToken(TOKEN)
        }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } }
    }

    private companion object {
        const val MINT = "mintToken"
        const val CHALLENGE = "challenge"
        const val RENEW = "renewToken"
        const val TOKEN = "DEADBEEF"
        const val ALWAYS = 10_000

        /** Past the 30 days a proof lasts. */
        val PROOF_EXPIRED = 31.days

        /** Every way the service can fail to hand out a challenge. */
        val CHALLENGE_FAULTS = listOf(
            BackendFault.SERVER_ERROR,
            BackendFault.MAINTENANCE,
            BackendFault.UNREACHABLE,
            BackendFault.MALFORMED,
        )

        val REFUSALS = setOf(
            ScreenMessage.DEVICE_MODIFIED,
            ScreenMessage.DEVICE_UNVERIFIABLE,
            ScreenMessage.APP_NOT_GENUINE,
        )
    }
}

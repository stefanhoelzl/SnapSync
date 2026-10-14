package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * **A proof SnapSync's service could not renew is the service's failure, never this phone's** (decision record
 * `changes/tell-service-faults`): an expired proof the service failed to renew shows the service's line; "this device
 * cannot be verified" is kept for a service that answered and still gave none; and no answer is never a refusal.
 */
class VerificationFaultIntegrationTest {

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "Unreachable server with an expired verification",
    )
    fun an_expired_proof_the_service_cannot_be_reached_to_renew_is_told_as_unreachable() =
        anExpiredProofThatMeets(BackendFault.UNREACHABLE, ServiceNotice.UNREACHABLE)

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "An update with an expired verification",
    )
    fun an_expired_proof_during_an_update_is_told_as_back_shortly() =
        anExpiredProofThatMeets(BackendFault.MAINTENANCE, ServiceNotice.UPDATING)

    private fun anExpiredProofThatMeets(fault: BackendFault, told: ServiceNotice) = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
            step(
                "left unopened longer than the proof lasts",
                act = { advance(PROOF_EXPIRED) },
                until = { settled { true } },
            )
        }
        whenever {
            verificationOutage(fault)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.ServiceFailing(told) }
            neverScreen<Layer.Joined> { it.health is SyncHealth.Unattested }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "The phone cannot give its proof",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "A failure without a verdict names no cause",
    )
    fun an_expired_proof_the_service_answers_but_gives_none_for_is_cannot_be_verified() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
            step(
                "left unopened longer than the proof lasts",
                act = { advance(PROOF_EXPIRED) },
                until = { settled { true } },
            )
        }
        whenever {
            // The service answers every renewal — its challenge was stale, twice — and no proof comes of it: what the
            // backend mock's `409` decodes as on the renewal route.
            backend.fail("renewToken", BackendFault.CLOSED, ANSWERED_WITHOUT_A_PROOF)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.Unattested(refusal = null) }
            neverScreen<Layer.Joined> { it.health is SyncHealth.ServiceFailing }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "No answer is not a refusal",
    )
    fun a_create_whose_proof_gets_no_answer_says_the_service_cannot_be_reached_never_a_refusal() = behaviour {
        given {
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
            step("its proof expired", act = { advance(PROOF_EXPIRED) }, until = { settled { true } })
            step(
                "opened while the service cannot be reached to verify it",
                act = {
                    verificationOutage(BackendFault.UNREACHABLE)
                    os.foreground()
                },
                until = { settled { true } },
            )
        }
        whenever { createTheEvent() }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UNREACHABLE }
            neverScreen<Layer.CreateEvent> { it.error?.let(REFUSALS::contains) == true }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A verification problem never loses a photo",
        scenario = "A renewal fails while the proof is still valid",
    )
    fun a_renewal_that_fails_while_the_proof_is_valid_keeps_sharing_and_tells_nothing() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
            step("its proof due for renewal, still valid", act = { advance(PROOF_DUE) }, until = { settled { true } })
        }
        whenever {
            verificationOutage(BackendFault.UNREACHABLE)
            takePhoto("P1")
            os.foreground()
        }
        then {
            event<WorldEvent.Transfer> { it is WorldEvent.SessionTransferCreated || it is WorldEvent.UploadJobCreated }
            neverScreen<Layer.Joined> { it.health is SyncHealth.Unattested || it.health is SyncHealth.ServiceFailing }
        }
    }

    private companion object {
        /** Past the 30 days a proof lasts. */
        val PROOF_EXPIRED = 31.days

        /** Inside the proof's last days, when it is renewed — still valid. */
        val PROOF_DUE = 28.days

        /** More renewals than an opening asks for. */
        const val ANSWERED_WITHOUT_A_PROOF = 100

        val REFUSALS =
            setOf(ScreenMessage.DEVICE_MODIFIED, ScreenMessage.DEVICE_UNVERIFIABLE, ScreenMessage.APP_NOT_GENUINE)
    }
}

/** SnapSync's service fails, as [fault] says, every attempt to verify this phone — and nothing else. */
suspend fun Acts.verificationOutage(fault: BackendFault) {
    listOf("challenge", "mintToken", "renewToken").forEach { backend.fail(it, fault, 10_000) }
}

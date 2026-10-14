package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * Silent pushes and the push registration, as the backend records them: the backend pushes a registered member when a
 * fellow member's photo becomes servable; the registration is installed on every cold start, a background one that
 * builds no screen included, and publishes a delivered token only when it differs from the last one the backend
 * accepted. The join and fresh-credential re-registrations are `PushRegistrationIntegrationTest`'s.
 */
@Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
class PushIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The notification token is used only to announce new photos",
        scenario = "Another member shares a photo",
    )
    fun a_fellow_members_completed_photo_is_pushed_to_a_registered_device() = behaviour {
        val event = given { joinedEvent().also { pushToken(TOKEN1) } }
        whenever { publicBackend.foreignDevice(DEV_F, listOf("FQ")) }
        then {
            // The completed foreign photo wakes the registered member.
            event<WorldEvent.PushSent> { it.event == event && it.token == TOKEN1 }
            // Only this device registered the token, and the foreign member that woke it is never pushed itself.
            never<WorldEvent.PushSent> { it.event != event || it.device == DEV_F || it.token != TOKEN1 }
        }
    }

    @Test
    fun a_device_without_a_token_is_never_pushed() = behaviour {
        given { joinedEvent() }
        whenever { publicBackend.foreignDevice(DEV_F, listOf("FQ")) }
        then { never<WorldEvent.PushSent>() }
    }

    @Test
    fun an_unchanged_token_redelivered_to_a_background_cold_start_is_not_published_again() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch() // a background wake: a silent push, a transfer relaunch
            pushToken(TOKEN1)
            // Process death, then another background cold start, which the OS answers with the same token.
            backgroundLaunch()
        }
        whenever { os.pushToken(TOKEN1) }
        then { never<WorldEvent.DeviceRegistered>() }
    }

    @Test
    fun a_rotation_learned_in_a_background_wake_is_published_from_it() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
            pushToken(TOKEN1)
            backgroundLaunch()
        }
        // Published from the background wake, not deferred to a foreground.
        whenever { os.pushToken(TOKEN2) }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN2 } }
    }

    @Test
    fun one_delivery_publishes_once() = behaviour {
        given {
            joinedEvent()
            inFront() // the host assembled, and every other path to the registration taken
        }
        // One collector, so one delivery publishes once.
        whenever { os.pushToken(TOKEN1) }
        then { times(1) { event<WorldEvent.DeviceRegistered>() } }
    }

    @Test
    fun an_unchanged_token_at_a_later_entry_publishes_nothing() = behaviour {
        given {
            joinedEvent()
            inFront()
            pushToken(TOKEN1)
        }
        // The next foreground entry's answer.
        whenever { os.pushToken(TOKEN1) }
        then { never<WorldEvent.DeviceRegistered>() }
    }

    /**
     * The credential-recovery loop: the backend rejects the token the registration carried, the app obtains a new one,
     * and the SAME call is sent once more and stored. That it is retried exactly once, and never without a token, is
     * `CredentialedBackendTest`'s (`:domain:services`).
     */
    @Test
    fun a_rejected_credential_is_recovered_and_the_registration_lands() = behaviour {
        given {
            joinedEvent()
            step("the next credential refused", act = { backend.refuseNextCredential() }, until = {})
        }
        whenever { os.pushToken(TOKEN1) }
        // The rejected call is answered by its retry.
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN1 } }
    }

    private companion object {
        const val TOKEN1 = "DEADBEEF"
        const val TOKEN2 = "FEEDFACE"
    }
}

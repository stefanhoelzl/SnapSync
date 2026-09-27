package app.snapsync.integration

import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Silent pushes and the push registration, read back off the backend (capability `receiving-photos`, "Registration
 * timing — launch, join, and rotation"; capability `sync-status`, "Push registration is started by the shared
 * composition"): the backend pushes a registered member when a fellow member's photo becomes servable; the
 * registration is installed on every cold start, a background one that builds no screen included, and publishes a
 * delivered token only when it differs from the last one the backend accepted. The join and fresh-credential
 * re-registrations are `PushRegistrationIntegrationTest`'s.
 */
class PushIntegrationTest {

    @Test
    fun a_fellow_members_completed_photo_is_pushed_to_a_registered_device() = rigTest {
        val event = createAndJoin()
        os("app", "onPushToken", "DEADBEEF")
        eventually(read = { registrations() }) { it == 1 }

        foreignDevice("DEV-F", "FQ")

        val pushes = pushesSent()
        assertTrue(pushes.isNotEmpty(), "the completed foreign photo woke the registered member")
        val own = deviceJson("backend/device-config").getValue("device").jsonPrimitive.content // this device
        assertTrue(pushes.all { (e, d, t) -> e == event && d == own && t == "DEADBEEF" })
    }

    @Test
    fun a_device_without_a_token_is_never_pushed() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        assertEquals(emptyList(), pushesSent())
    }

    @Test
    fun a_background_cold_start_registers_and_publishes_only_a_rotation() = rigTest {
        createAndJoin()
        device("relaunch", "scene" to "false") // a background wake: a silent push, a transfer relaunch
        os("app", "onPushToken", "TOKEN1")
        eventually(read = { registrations() }) { it == 1 }

        // Process death, then another background cold start: the OS re-delivers the same token.
        device("relaunch", "scene" to "false")
        os("app", "onPushToken", "TOKEN1")
        settle()
        assertEquals(1, registrations(), "an unchanged token is not re-published at launch")

        // A rotation learned in that background wake is published from it, not deferred to a foreground.
        os("app", "onPushToken", "TOKEN2")
        eventually(read = { registrations() }) { it == 2 }
        assertEquals("TOKEN2", deviceConfig()?.first)
    }

    @Test
    fun one_delivery_publishes_once_and_an_unchanged_one_at_a_later_entry_publishes_nothing() = rigTest {
        createAndJoin()
        foreground() // the host assembled, and every other path to the registration taken
        os("app", "onPushToken", "TOKEN1")
        eventually(read = { registrations() }) { it == 1 }
        settle()
        assertEquals(1, registrations(), "one collector, so one delivery publishes once")

        os("app", "onPushToken", "TOKEN1") // the next foreground entry's answer
        settle()
        assertEquals(1, registrations())
    }

    /**
     * The credential-recovery loop (capability `privacy-security`, "Only a rejected credential is invalidated, and only
     * that one"): the backend rejects the token the registration carried, the app obtains a new one, and the SAME call
     * is sent once more and stored. That it is retried exactly once, and never without a token, is
     * `CredentialedBackendTest`'s (`:domain:services`).
     */
    @Test
    fun a_rejected_credential_is_recovered_and_the_registration_lands() = rigTest {
        createAndJoin()
        device("backend/refuse-credential")

        os("app", "onPushToken", "TOKEN1")

        // The rejected call is answered by its retry; the recovered credential then re-registers too, as any new one does.
        eventually<String?>(read = { deviceConfig()?.first }) { it == "TOKEN1" }
    }

    /** Long enough for a publish the registration would make to land; what follows is about absence. */
    private suspend fun settle() = delay(300)
}

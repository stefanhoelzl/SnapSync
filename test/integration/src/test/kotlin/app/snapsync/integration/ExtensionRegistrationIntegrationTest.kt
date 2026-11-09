package app.snapsync.integration

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **When the app registers iOS's own background uploader** (decision record `changes/own-work-per-wake`), read off the
 * operating system's record: only while it may upload — a full grant, and the uploader switched on — and only when iOS
 * does not already hold the registration. A grant widened to full registers it; a launch over a registration iOS holds
 * registers nothing again; an uploader switched off is never registered.
 */
class ExtensionRegistrationIntegrationTest {

    @Test
    fun a_member_who_joined_under_limited_access_registers_ios_uploader_once_access_is_full() = behaviour {
        given {
            iosUploaderCarried()
            photoAccess(PhotoGrant.LIMITED)
            createdEvent()
            step("joined", act = { confirmJoin() }, until = {
                event<WorldEvent.MemberJoined>()
                never<WorldEvent.ExtensionRegistered> { it.on }
                settled { true }
            })
        }

        whenever { library.permission(PhotoGrant.GRANTED) }
        then { event<WorldEvent.ExtensionRegistered> { it.on } }
    }

    @Test
    fun a_launch_over_a_registration_ios_holds_registers_nothing_again() = behaviour {
        given {
            iosUploaderCarried()
            createdEvent()
            step("joined, iOS's uploader registered", act = { confirmJoin() }, until = {
                event<WorldEvent.ExtensionRegistered> { it.on }
                settled { true }
            })
        }

        whenever { host.relaunch() }
        then {
            never<WorldEvent.ExtensionRegistered>()
            settled { true }
        }
    }

    @Test
    fun a_join_with_ios_uploader_switched_off_never_registers_it() = behaviour {
        given {
            iosUploaderCarried()
            appUploadsOnly()
            createdEvent()
        }

        whenever { confirmJoin() }
        then {
            event<WorldEvent.MemberJoined>()
            never<WorldEvent.ExtensionRegistered> { it.on }
            settled { true }
        }
    }
}

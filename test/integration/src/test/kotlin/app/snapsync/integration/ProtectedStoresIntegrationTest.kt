package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PreferenceItem
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **The device's protected stores, unreadable or refusing** — a phone not unlocked since it restarted, a phone restored
 * without the event's key, a Keychain or a preference that refuses a call — and what the member sees of each
 * (`changes/archive/2026-10-15-unavailable-keychain-and-preferences`).
 *
 * The locked phone is ONE device fact (`device.lock()`): the Keychain, the databases and the files sealed at once, as
 * Apple documents the classes the app writes, before the first unlock since a restart. A running phone cannot enter
 * that state, so each behaviour plays the restart: the lock, then a cold background launch. A phone locked AFTER a first
 * unlock seals none of the stores the app writes, so it needs no lever at all.
 */
class ProtectedStoresIntegrationTest {

    /** Many refusals: every read of a preference the app makes while the behaviour runs. */
    private val always = 1_000

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "A choice the phone cannot read",
    )
    fun a_mobile_data_choice_the_phone_cannot_read_is_off_on_screen_and_for_every_photo() = behaviour {
        given {
            joinedEvent()
            step(
                "the mobile-data choice unreadable from now on",
                act = { preferences.fail(PreferenceItem.MOBILE_DATA, StoreFault.READ, always) },
                until = {},
            )
        }

        whenever { host.relaunch() }
        then {
            event<WorldEvent.UiChanged> { it.ui.layer is Layer.Joined && !it.ui.mobileData.on }
            event<WorldEvent.PreferencesFaulted> { it.item == PreferenceItem.MOBILE_DATA }
        }

        whenever {
            network.set(NetworkState.RESTRICTED)
            takePhoto("P1")
            os.foreground()
        }
        then { screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.waitingForWifi == true } }
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "A change that cannot be saved",
    )
    fun a_mobile_data_change_that_cannot_be_saved_keeps_the_choice_in_effect() = behaviour {
        given {
            joinedEvent()
            step(
                "the next mobile-data write refused",
                act = { preferences.fail(PreferenceItem.MOBILE_DATA, StoreFault.WRITE) },
                until = {},
            )
        }

        whenever { tap(UiIntent.MobileData(on = false)) }
        then {
            event<WorldEvent.PreferencesFaulted> { it.item == PreferenceItem.MOBILE_DATA && it.kind == StoreFault.WRITE }
            event<WorldEvent.UiChanged> { it.ui.mobileData.on && it.ui.mobileData.notSaved }
            never<WorldEvent.UiChanged> { !it.ui.mobileData.on }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A join whose key the phone cannot keep",
    )
    fun a_join_whose_key_the_phone_cannot_keep_fails_with_retry_and_enrols_nothing() = behaviour {
        val event = given {
            val event = createdEvent()
            step(
                "the Keychain refuses the next write of the event's key",
                act = { keychain.fail(KeychainItem.EVENT_KEY, StoreFault.WRITE) },
                until = {},
            )
            event
        }

        whenever { confirmJoin() }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.EVENT_KEY && it.kind == StoreFault.WRITE }
            screen<Layer.JoiningEvent> { (it.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.CommitFailed }
            never<WorldEvent.BackendReceived> { it.operation == "joinEvent" }
            neverScreen<Layer.Joined>()
        }

        whenever { tap(UiIntent.RetryJoin) }
        then {
            event<WorldEvent.BackendReceived> { it.operation == "joinEvent" }
            screen<Layer.Joined> { it.membership.eventId == event }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A locked phone delays uploads and never ends the membership",
        scenario = "A wake before the first unlock does not make the phone a new member",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "The first frame is never a guessed state",
        scenario = "A locked-device wake keeps the membership",
    )
    fun a_wake_before_the_first_unlock_keeps_the_membership_and_the_member() = behaviour {
        val event = given {
            val event = joinedEvent()
            step(
                "the phone restarted, not yet unlocked",
                act = {
                    device.lock()
                    host.relaunch(scene = false)
                },
                until = { settled { true } },
            )
            event
        }

        whenever {
            takePhoto("LOCKED-1")
            heartbeat()
        }
        then {
            never<WorldEvent.BackendReceived> { it.operation == "joinEvent" || it.operation == "leaveEvent" }
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
        }

        whenever {
            device.unlock()
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            event<WorldEvent.SessionTransferCreated> { it.key.startsWith("LOCKED-1") }
            never<WorldEvent.BackendReceived> { it.operation == "joinEvent" }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that lost the event's key asks for its invite",
        scenario = "A locked phone is not mistaken for a lost key",
    )
    fun a_phone_locked_since_it_restarted_is_not_mistaken_for_a_lost_key() = behaviour {
        given {
            joinedEvent()
            step(
                "the phone restarted, not yet unlocked",
                act = {
                    device.lock()
                    host.relaunch(scene = false)
                },
                until = { settled { true } },
            )
        }

        whenever { heartbeat() }
        then { settled { true } }

        whenever {
            device.unlock()
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.inviteUrl != null }
            neverScreen<Layer.Joined> { it.health == SyncHealth.KeyLost }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A locked phone delays uploads and never ends the membership",
        scenario = "Uploads while locked",
    )
    fun photos_upload_in_the_background_while_the_phone_stays_locked() = behaviour {
        // Locked AFTER a first unlock, every store the app writes stays readable — the Keychain's after-first-unlock
        // class and the files' until-first-user-authentication — so the phone in the member's pocket is no lever at
        // all: the app in the background, woken by the operating system, with nothing sealed.
        given {
            joinedEvent()
            step("the app sent to the background", act = { os.background() }, until = { settled { true } })
        }

        whenever {
            takePhoto("POCKET-1")
            heartbeat()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key.startsWith("POCKET-1") } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that lost the event's key asks for its invite",
        scenario = "A restored phone asks for the invite",
    )
    fun a_restored_phone_without_the_key_asks_for_the_invite_and_moves_no_photo() = behaviour {
        given {
            joinedEvent()
            step(
                "restored onto a new phone without the event's key",
                act = { keychain.forget(KeychainItem.EVENT_KEY) },
                until = {},
            )
        }

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.KeyLost && it.inviteUrl == null } }

        whenever {
            takePhoto("OWN-1")
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            heartbeat()
        }
        then {
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.DownloadStarted>()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Reopening the current event's invite changes nothing",
        scenario = "Reopening the invite restores a lost key",
    )
    fun reopening_the_whole_invite_restores_the_lost_key_in_place() = behaviour {
        val invite = given {
            val event = joinedEvent()
            val invite = inviteFor(event)
            step("the event's key lost", act = { keychain.forget(KeychainItem.EVENT_KEY) }, until = {})
            step(
                "the joined screen asks for the invite",
                act = { os.foreground() },
                until = { screen<Layer.Joined> { it.health == SyncHealth.KeyLost } },
            )
            invite
        }

        whenever { openLink(invite) }
        then {
            screen<Layer.Joined> { it.health != SyncHealth.KeyLost && it.inviteUrl != null }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Reopening the current event's invite changes nothing",
        scenario = "An incomplete invite does not restore a lost key",
    )
    fun an_invite_without_its_key_does_not_restore_a_lost_key() = behaviour {
        val event = given {
            val event = joinedEvent()
            step("the event's key lost", act = { keychain.forget(KeychainItem.EVENT_KEY) }, until = {})
            step(
                "the joined screen asks for the invite",
                act = { os.foreground() },
                until = { screen<Layer.Joined> { it.health == SyncHealth.KeyLost } },
            )
            event
        }

        whenever { openLink("$LINK_ORIGIN/join/$event") }
        then {
            neverScreen<Layer.Joined> { it.health != SyncHealth.KeyLost }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "A locked phone delays uploads and never ends the membership")
    fun a_device_identity_the_keychain_will_not_read_is_never_replaced() = behaviour {
        val event = given {
            val event = joinedEvent()
            step(
                "a background launch whose device-id reads are refused",
                act = {
                    keychain.fail(KeychainItem.DEVICE_ID, StoreFault.READ, always)
                    host.relaunch(scene = false)
                },
                until = { settled { true } },
            )
            event
        }

        whenever {
            takePhoto("UNREAD-1")
            heartbeat()
        }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.DEVICE_ID && it.kind == StoreFault.READ }
            never<WorldEvent.BackendReceived> { it.operation == "joinEvent" || it.operation == "leaveEvent" }
            never<WorldEvent.SessionTransferCreated>()
        }

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.membership.eventId == event } }
    }
}

package app.snapsync.integration

import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **The proof and identity this phone keeps in its Keychain, when the Keychain fails them** — read off what the service
 * receives: a rejected proof the Keychain no longer holds is replaced and the refused call goes through, one it cannot
 * read leaves the call for the next delivery; a new proof, or the key it was made with, that the Keychain will not keep
 * is obtained again at the next call; and a phone whose Keychain lost its identity makes itself a new one, kept or not,
 * and still creates an event.
 */
class KeptProofIntegrationTest {

    @Test
    fun a_rejected_proof_the_keychain_no_longer_holds_is_replaced_and_the_call_goes_through() = behaviour {
        given { inFront() }

        // The app still carries the proof in memory; the Keychain lost it meanwhile.
        whenever {
            keychain.forget(KeychainItem.ATTEST_TOKEN)
            backend.refuseNextCredential()
            os.pushToken(TOKEN)
        }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } }
    }

    @Test
    fun a_rejected_proof_the_keychain_cannot_read_leaves_the_call_for_the_next_delivery() = behaviour {
        given { inFront() }

        whenever {
            keychain.fail(KeychainItem.ATTEST_TOKEN, StoreFault.READ)
            refusedRegistration()
        }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.ATTEST_TOKEN && it.kind == StoreFault.READ }
            never<WorldEvent.DeviceRegistered>()
            settled { true }
        }

        whenever { os.pushToken(TOKEN) }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } }
    }

    @Test
    fun a_renewed_proof_the_keychain_will_not_keep_is_renewed_again_at_the_next_call() = behaviour {
        given { inFront() }

        whenever {
            keychain.forget(KeychainItem.ATTEST_TOKEN)
            keychain.fail(KeychainItem.ATTEST_TOKEN, StoreFault.WRITE)
            refusedRegistration()
        }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.ATTEST_TOKEN && it.kind == StoreFault.WRITE }
            never<WorldEvent.DeviceRegistered>()
            settled { true }
        }

        whenever { os.pushToken(TOKEN) }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } }
    }

    @Test
    fun a_proof_whose_key_the_keychain_will_not_keep_is_made_again_at_the_next_call() = behaviour {
        given { inFront() }

        // The renewal key is gone, so the phone proves itself afresh — and the Keychain refuses the new key's id.
        whenever {
            keychain.forget(KeychainItem.ATTEST_KEY_ID)
            keychain.fail(KeychainItem.ATTEST_KEY_ID, StoreFault.WRITE)
            refusedRegistration()
        }
        then {
            event<WorldEvent.BackendReceived> { it.operation == MINT }
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.ATTEST_KEY_ID && it.kind == StoreFault.WRITE }
            never<WorldEvent.DeviceRegistered>()
            settled { true }
        }

        whenever { os.pushToken(TOKEN) }
        then {
            event<WorldEvent.BackendReceived> { it.operation == MINT }
            event<WorldEvent.DeviceRegistered> { it.token == TOKEN }
        }
    }

    @Test
    fun a_phone_whose_keychain_lost_its_identity_makes_a_new_one_and_creates_an_event() = behaviour {
        given {
            step("restored onto a new phone: the Keychain holds no identity", act = {
                keychain.forget(KeychainItem.DEVICE_ID)
                host.relaunch()
            }, until = { settled { true } })
            inFront()
        }

        whenever { createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END) }
        then {
            event<WorldEvent.EventCreated>()
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
        }
    }

    @Test
    fun a_new_identity_the_keychain_would_not_keep_is_made_again_and_the_phone_creates_an_event() = behaviour {
        given {
            step("restored onto a new phone, the Keychain refusing the first identity it is handed", act = {
                keychain.forget(KeychainItem.DEVICE_ID)
                keychain.fail(KeychainItem.DEVICE_ID, StoreFault.WRITE)
                host.relaunch()
            }, until = { settled { true } })
        }

        whenever {
            os.foreground()
            createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
        }
        then {
            event<WorldEvent.KeychainFaulted> { it.item == KeychainItem.DEVICE_ID && it.kind == StoreFault.WRITE }
            event<WorldEvent.EventCreated>()
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
        }
    }

    /** The service refuses the proof the app carries, and the push service hands the app a token to register. */
    private suspend fun Acts.refusedRegistration() {
        backend.refuseNextCredential()
        os.pushToken(TOKEN)
    }

    private companion object {
        const val TOKEN = "DEADBEEF"
        const val MINT = "mintToken"
    }
}

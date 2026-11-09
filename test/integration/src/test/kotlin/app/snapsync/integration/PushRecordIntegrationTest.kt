package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PreferenceItem
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A push registration the record cannot vouch for is published** (decision record `changes/own-work-per-wake`, D12),
 * as the backend records it: a delivered token is held back only when the record says the backend already has it — a
 * record that cannot be read says nothing, so the token goes out again.
 */
@Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
class PushRecordIntegrationTest {

    @Test
    fun an_unchanged_token_is_published_again_when_the_record_of_the_last_one_cannot_be_read() = behaviour {
        given {
            joinedEvent()
            inFront()
            pushToken(TOKEN)
            step(
                "the record of the last registration unreadable",
                act = { preferences.fail(PreferenceItem.PUSH_REGISTRATION, StoreFault.READ, ALWAYS) },
                until = {},
            )
        }

        whenever { os.pushToken(TOKEN) }
        then { event<WorldEvent.DeviceRegistered> { it.token == TOKEN } }
    }

    private companion object {
        const val TOKEN = "DEADBEEF"
        const val ALWAYS = 1_000
    }
}

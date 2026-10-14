package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.ui.state.Layer
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

/**
 * **The create screen's draft follows the app's foreground life** (capability `create-event`, "The start follows the
 * clock until the host chooses the range" and "A long absence starts a fresh draft"), driven at the OS boundary: the
 * real lifecycle entry points and the mocked clock. Every return to the foreground moves an untouched start to now and
 * keeps what the host typed; only a return after 15 minutes or more away starts a fresh draft.
 */
@Verifies(spec = "create-event", requirement = "A long absence starts a fresh draft")
class CreateDraftIntegrationTest {

    @Test
    fun a_return_within_15_minutes_keeps_the_draft_and_a_later_one_starts_afresh() = behaviour(startAt = T0) {
        lateinit var first: Seen<Layer.CreateEvent>
        given {
            inFront()
            step("a name typed", act = { user("createName", "name" to "Party") }, until = {
                first = screen<Layer.CreateEvent> { it.form.name == "Party" }
            })
            step("away", act = { os.background() }, until = { settled { true } })
        }

        // Back within 15 minutes: the same draft, the name kept, the untouched start moved to now.
        whenever {
            advanceTo(T0_PLUS_14_MIN)
            os.foreground()
        }
        then {
            screen<Layer.CreateEvent> {
                it.form.picker.from == local(T0_PLUS_14_MIN) && it.form.draft == first.value.form.draft &&
                    it.form.name == "Party"
            }
        }

        // Back after 15 minutes or more: a fresh draft, at now.
        whenever {
            os.background()
            advanceTo(T0_PLUS_29_MIN)
            os.foreground()
        }
        then {
            screen<Layer.CreateEvent> {
                it.form.draft > first.value.form.draft && it.form.name == "" && it.form.picker.from == local(T0_PLUS_29_MIN)
            }
        }
    }

    /** [iso] as the device's wall clock states it — the mocked clock's zone, UTC. */
    private fun local(iso: String): LocalDateTime = Instant.parse(iso).toLocalDateTime(TimeZone.UTC)

    private companion object {
        const val T0 = "2026-06-10T12:00:00Z"
        const val T0_PLUS_14_MIN = "2026-06-10T12:14:00Z"
        const val T0_PLUS_29_MIN = "2026-06-10T12:29:00Z"
    }
}

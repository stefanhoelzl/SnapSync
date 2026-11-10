package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **Background App Refresh switched off** — the member's choice in iOS's Settings, or a profile's: iOS then refuses
 * every background task the app asks for, so its own wake-ups never come. Its photos still travel on every opening of
 * the app.
 */
class BackgroundRefreshIntegrationTest {

    @Test
    @Verifies(spec = "delivery", requirement = "Photos travel without the app being opened")
    fun with_background_refresh_off_no_wake_is_scheduled_and_a_photo_travels_on_opening() = behaviour {
        given {
            step(
                "Background App Refresh off",
                act = { deviceConditions.set(mapOf(DiagnosticKeys.BACKGROUND_REFRESH to "denied")) },
                until = {},
            )
            appUploadsOnly()
            joinedEvent()
        }

        whenever {
            takePhoto("P")
            os.foreground()
        }
        then {
            event<WorldEvent.ManifestPublished> { "P" in it.added }
            never<WorldEvent.WakeScheduled>()
        }
    }
}

package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **The update notice's one remedy, on an Android build** (`openspec/specs/app-update-required`, "The notice says what
 * to install and where"), one scenario per test, over the real composed stack.
 *
 * The store page is the build's own fact ([BuildLevers.store]), read as the app starts — a Google Play page, or none
 * while the listing is not public. The notice is reached as any outdated build reaches it: the service names a minimum
 * the build is below.
 */
class StorePageIntegrationTest {

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice says what to install and where",
        scenario = "Opening Google Play",
    )
    fun the_notice_names_google_play_and_its_button_opens_the_build_s_page() = behaviour {
        given {
            listedIn(GOOGLE_PLAY, PLAY_PAGE)
            joinedEvent()
            outdated()
        }

        whenever { tap(UiIntent.OpenAppStore) }
        then { event<WorldEvent.UrlOpened> { it.url == PLAY_PAGE } }
    }

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice says what to install and where",
        scenario = "Opening the App Store",
    )
    fun the_notice_names_the_app_store_and_its_button_opens_the_build_s_page() = behaviour {
        given {
            listedIn(APP_STORE, APP_STORE_PAGE)
            joinedEvent()
            outdated(APP_STORE)
        }

        whenever { tap(UiIntent.OpenAppStore) }
        then { event<WorldEvent.UrlOpened> { it.url == APP_STORE_PAGE } }
    }

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice says what to install and where",
        scenario = "No store page known",
    )
    fun a_build_with_no_store_page_says_an_update_is_required_and_offers_no_button() = behaviour {
        given {
            step("a build listed nowhere yet", act = {
                build.store()
                host.relaunch()
            }, until = { settled { true } })
            joinedEvent()
        }

        whenever {
            backend.minAppVersion(MINIMUM)
            build.declare(OLDER)
            os.foreground()
        }
        then { screen<Layer.UpdateRequired> { it.minimumVersion == MINIMUM && it.store == null } }
    }

    /** The build is listed in the store [name], at [page], from its next start — which the app makes now. */
    private suspend fun Given.listedIn(name: String, page: String) = step("a build listed in $name", act = {
        build.store(name, page)
        host.relaunch()
    }, until = { settled { true } })

    /** The service raised its minimum past this build, and the app met it: the notice shows, naming the store. */
    private suspend fun Given.outdated(store: String = GOOGLE_PLAY) = step("outdated", act = {
        backend.minAppVersion(MINIMUM)
        build.declare(OLDER)
        os.foreground()
    }, until = { screen<Layer.UpdateRequired> { it.minimumVersion == MINIMUM && it.store == store } })

    private companion object {
        const val GOOGLE_PLAY = "Google Play"
        const val PLAY_PAGE = "https://play.google.com/store/apps/details?id=app.snapsync"
        const val APP_STORE = "App Store"
        const val APP_STORE_PAGE = "https://apps.apple.com/app/snapsync/id6740000000"
        const val MINIMUM = "0.4"
        const val OLDER = "0.3"
    }
}

package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.request.OwnPage
import app.snapsync.format.OwnPageText
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.LinkNotice
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **A page the phone will not open is told** (`openspec/specs/app-experience`), one scenario per test, over the real
 * composed stack. The phone refuses a page the way a device with no app for it does: the platform answers that it did
 * not open it ([app.snapsync.levers.SystemUiLevers.unclaimed]). Field refusals of this kind are Bugsink SNAPSYNC-71.
 */
class RefusedLinkIntegrationTest {

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "A page the phone will not open is told",
        scenario = "The phone does not open the Privacy Policy",
    )
    fun a_privacy_policy_the_phone_does_not_open_is_told_with_the_site_s_address() = behaviour {
        given { refused(OwnPageText.url(OwnPage.PRIVACY_POLICY)) }

        whenever { tap(UiIntent.OpenLink(AppLink.PRIVACY_POLICY)) }
        then {
            event<WorldEvent.UiChanged> { it.ui.overlays.linkNotice == LinkNotice.Site(OwnPageText.address()) }
            never<WorldEvent.UrlOpened>()
            screen<Layer.CreateEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "A page the phone will not open is told",
        scenario = "The notice clears itself",
    )
    fun the_word_on_a_page_that_did_not_open_clears_itself() = behaviour {
        given { refused(OwnPageText.url(OwnPage.WEBSITE)) }

        whenever { tap(UiIntent.OpenLink(AppLink.WEBSITE)) }
        then {
            event<WorldEvent.UiChanged> { it.ui.overlays.linkNotice != null }
            settled { true }
        }

        whenever { advance(NOTICE_SHOWN) }
        then { event<WorldEvent.UiChanged> { it.ui.overlays.linkNotice == null } }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "A page the phone will not open is told",
        scenario = "The phone does not open the store",
    )
    fun a_store_page_the_phone_does_not_open_is_told_and_the_update_notice_stays() = behaviour {
        given {
            step("a build listed in the App Store", act = {
                build.store(APP_STORE, APP_STORE_PAGE)
                host.relaunch()
            }, until = { settled { true } })
            joinedEvent()
            step("outdated", act = {
                backend.minAppVersion(MINIMUM)
                build.declare(OLDER)
                os.foreground()
            }, until = { screen<Layer.UpdateRequired> { it.store == APP_STORE } })
            refused(APP_STORE_PAGE)
        }

        whenever { tap(UiIntent.OpenAppStore) }
        then {
            event<WorldEvent.UiChanged> {
                it.ui.overlays.linkNotice == LinkNotice.Store(APP_STORE) && it.ui.layer is Layer.UpdateRequired
            }
            never<WorldEvent.UrlOpened>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "A page the phone will not open is told",
        scenario = "The page opens",
    )
    fun a_page_the_phone_opens_is_not_told() = behaviour {
        whenever { tap(UiIntent.OpenLink(AppLink.WEBSITE)) }
        then {
            event<WorldEvent.UrlOpened> { it.url == OwnPageText.url(OwnPage.WEBSITE) }
            never<WorldEvent.UiChanged> { it.ui.overlays.linkNotice != null }
            settled { true }
        }
    }

    /** No app on the phone opens [url]: the platform answers that it did not open it. */
    private suspend fun Given.refused(url: String) =
        step("the phone opens no page at $url", act = { systemUi.unclaimed(listOf(url)) }, until = {})

    private companion object {
        const val APP_STORE = "App Store"
        const val APP_STORE_PAGE = "https://apps.apple.com/app/snapsync/id6740000000"
        const val MINIMUM = "0.4"
        const val OLDER = "0.3"

        /** Longer than the word stays. */
        val NOTICE_SHOWN = 5.seconds
    }
}

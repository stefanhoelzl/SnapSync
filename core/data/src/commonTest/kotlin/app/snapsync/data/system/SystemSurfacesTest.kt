package app.snapsync.data.system

import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.OwnPage
import app.snapsync.domain.version.Store
import app.snapsync.format.OwnPageText
import app.snapsync.ports.Handoff
import app.snapsync.ports.SystemUi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the member's taps hand to the platform: each surface asked for as it was, and a refusal — the member tapped and
 * nothing happened — answered like an acceptance, never thrown: nothing acts on it.
 */
class SystemSurfacesTest {

    private class Ui(private val answer: Handoff) : SystemUi {
        val asked = mutableListOf<String>()

        override suspend fun share(text: String, title: String): Handoff = answer.also { asked += "share $text $title" }

        override suspend fun openUrl(url: String): Handoff = answer.also { asked += "open $url" }

        override fun openSettings() {
            asked += "settings"
        }
    }

    @Test
    fun `each surface is asked for as the member asked`() = runTest {
        val ui = Ui(Handoff.Accepted)
        val surfaces = SystemSurfaces(ui)
        surfaces.share("https://snapsync.app/join", "Anna's Birthday")
        surfaces.open(AppLinkRequest.StorePage(Store("App Store", "https://apps.apple.com/app")))
        surfaces.open(AppLinkRequest.SitePage(OwnPage.PRIVACY_POLICY))
        surfaces.openSettings()
        assertEquals(
            listOf(
                "share https://snapsync.app/join Anna's Birthday",
                "open https://apps.apple.com/app",
                "open ${OwnPageText.url(OwnPage.PRIVACY_POLICY)}",
                "settings",
            ),
            ui.asked,
        )
    }

    @Test
    fun `a surface the platform refused is recorded, and answered like any other`() = runTest {
        val ui = Ui(Handoff.Refused("no window"))
        val surfaces = SystemSurfaces(ui)
        surfaces.share("https://snapsync.app/join", "Party")
        surfaces.open(AppLinkRequest.SitePage(OwnPage.WEBSITE))
        assertEquals(2, ui.asked.size)
    }
}

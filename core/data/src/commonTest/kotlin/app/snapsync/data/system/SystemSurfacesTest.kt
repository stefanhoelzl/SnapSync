@file:OptIn(CompositionOnly::class)

package app.snapsync.data.system

import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.OwnPage
import app.snapsync.domain.version.Store
import app.snapsync.format.OwnPageText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.ports.Handoff
import app.snapsync.ports.Observed
import app.snapsync.ports.RefusalCircumstances
import app.snapsync.ports.SystemUi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the member's taps hand to the platform: each surface asked for as it was, a page's answer handed back so the
 * member can be told, and a refusal — the member tapped and nothing happened — recorded with what the platform said of
 * itself, never thrown.
 */
class SystemSurfacesTest {
    private val lines = mutableListOf<LogLine>()

    init {
        Log.install(
            listOf(
                object : LogWriter {
                    override fun write(line: LogLine) {
                        lines += line
                    }
                },
            ),
        )
    }

    @AfterTest
    fun uninstall() {
        Log.install(emptyList())
    }

    private class Ui(private val answer: Handoff) : SystemUi {
        val asked = mutableListOf<String>()

        override suspend fun share(text: String, title: String): Handoff = answer.also { asked += "share $text $title" }

        override suspend fun openUrl(url: String): Handoff = answer.also { asked += "open $url" }

        override fun openSettings() {
            asked += "settings"
        }
    }

    private val restricted = RefusalCircumstances.Read(
        inFront = Observed.Seen(true),
        claimed = Observed.Seen(false),
        scene = Observed.Unread("no scene is connected"),
    )

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
    fun `a page's answer is handed back, and an opened one is not logged`() = runTest {
        val surfaces = SystemSurfaces(Ui(Handoff.Accepted))
        assertEquals(Handoff.Accepted, surfaces.open(AppLinkRequest.SitePage(OwnPage.WEBSITE)))
        assertEquals(emptyList(), lines.filter { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a refused page is an error carrying what the platform said of itself`() = runTest {
        val refused = Handoff.Refused("iOS did not open https://snapsync.app", restricted)
        val surfaces = SystemSurfaces(Ui(refused))
        assertEquals(refused, surfaces.open(AppLinkRequest.SitePage(OwnPage.WEBSITE)))
        val line = lines.single()
        assertEquals(LogLevel.ERROR, line.level)
        assertEquals("nothing was handed off", line.event)
        assertEquals(
            mapOf(
                "what" to "link",
                "reason" to "iOS did not open https://snapsync.app",
                "in_front" to "true",
                "claimed" to "false",
                "scene" to "unread (no scene is connected)",
            ),
            line.fields.associate { it.name to it.value.toString() },
        )
    }

    @Test
    fun `a refusal whose reason is the whole story names only that`() = runTest {
        val surfaces = SystemSurfaces(Ui(Handoff.Refused("no window", RefusalCircumstances.NotAsked)))
        surfaces.share("https://snapsync.app/join", "Party")
        val line = lines.single()
        assertEquals("nothing was handed off", line.event)
        assertEquals(listOf("what", "reason"), line.fields.map { it.name })
    }
}

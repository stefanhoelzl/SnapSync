package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class HardCodedTextTest {

    private fun ui(code: String) = HardCodedUiText().lint(code).map { it.message }
    private fun system(code: String) = HardCodedSystemText().lint(code).map { it.message }

    @Test
    fun `words in a UI module are findings wherever they are written`() {
        val findings = ui(
            """
            fun Text(text: String) {}
            fun AppNotice(title: String, body: String) {}
            fun label(n: Int) = "${'$'}n photos"
            fun screen() {
                Text("In sync")
                AppNotice(title = "Leave?", body = "Photos stay.")
            }
            """.trimIndent(),
        )
        assertEquals(4, findings.size, findings.toString())
    }

    @Test
    fun `punctuation, annotations, developer messages, tags, animation labels and skeletons are not words`() {
        val findings = ui(
            """
            fun Text(text: String) {}
            fun testTag(tag: String) {}
            fun animateFloat(label: String) {}
            fun rememberInfiniteTransition(label: String) {}
            class Dates { fun format(skeleton: String) = skeleton }
            @Suppress("MagicNumber")
            fun screen(range: String, phrase: String, dates: Dates) {
                Text("${'$'}range · ${'$'}phrase")
                Text("--")
                error("a loaded phase always resolves a range")
                require(true) { "never" }
                testTag("join-button")
                animateFloat(label = "pulse-alpha")
                rememberInfiniteTransition(label = "pulse")
                dates.format("yMMMd")
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings)
    }

    @Test
    fun `a suppressed diagnostic label passes`() {
        val findings = ui(
            """
            @Suppress("HardCodedUiText")
            fun screenLabel(joined: Boolean) = if (joined) "Joined" else "CreateEvent"
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings)
    }

    @Test
    fun `system text is a finding only where the OS shows it`() {
        val findings = system(
            """
            class NotificationChannel(id: String, name: String, importance: Int)
            class Builder { fun setContentTitle(t: String) = this; fun setTitle(t: String) = this }
            fun notify(name: String) {
                NotificationChannel("snapsync.sharing", "Sharing photos", 1)
                NotificationChannel("snapsync.sharing", name, 1)
                Builder().setContentTitle("Sharing photos").setTitle("restarts:")
                println("not shown to anyone")
            }
            """.trimIndent(),
        )
        assertEquals(2, findings.size, findings.toString())
    }
}

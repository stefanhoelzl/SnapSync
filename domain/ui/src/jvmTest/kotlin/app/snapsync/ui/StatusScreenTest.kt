package app.snapsync.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.snapsync.permission.PermissionStatus
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.SystemCutoffFormatter
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.Rule

// A representative invite link — any string renders a QR; the encoding is pinned in capability:config.
private const val SAMPLE_INVITE = "https://snapsync.stho.net/join#v=3&d=eyJldmVudElkIjoiMSJ9"

private fun joined(health: SyncHealth) = UiState.Joined(health)
private val inSync = joined(SyncHealth.InSync)
private val syncing = joined(SyncHealth.Syncing(Arrow.PULSING, Arrow.HIDDEN))
private val syncPending = joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN))

/** A clock the test can move, to prove the start default does not drift. */
private class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** A real formatter on a fixed UTC clock — deterministic, and it actually converts. */
private fun fixedCutoff() = SystemCutoffFormatter(
    clock = object : Clock { override fun now(): Instant = Instant.parse("2026-07-06T12:00:00Z") },
    zone = TimeZone.UTC,
)

class StatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- the not-started clock line ----

    @Test
    fun `the not-started health renders a clock line naming the start, below the QR`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NotStarted("2026-07-04T18:00:00Z")),
                inviteUrl = "https://snapsync.stho.net/join#v=3&d=abc",
                cutoff = fixedCutoff(),
            )
        }
        // Rendered in the DEVICE's local zone (UTC here), in the same one-line slot every other status
        // uses — the joined layer never grows a second line.
        rule.onNodeWithText("Starts 4 Jul, 18:00").assertExists()
        // It is information, not an action: no sync arrows, no "In sync".
        rule.onNodeWithText("In sync").assertDoesNotExist()
        rule.onNodeWithText("Synchronization pending …").assertDoesNotExist()
    }

    // ---- create layer ----

    @Test
    fun `create screen shows the name input and the scan-to-join hint`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        rule.onNodeWithText("Start an event").assertExists()
        rule.onNodeWithText("Or scan a QR code in the Camera app to join one.").assertExists()
        rule.onNodeWithText("Event name").assertExists()
        rule.onNodeWithText("Create event").assertExists()
    }

    @Test
    fun `invalid deeplink error shows on the create screen`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), transientError = "That QR code wasn't valid.")
        }
        rule.onNodeWithText("That QR code wasn't valid.").assertExists()
    }

    @Test
    fun `a create failure shows its inline error on the create screen`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(error = "Couldn't reach the server.")) }
        rule.onNodeWithText("Couldn't reach the server.").assertExists()
    }

    @Test
    fun `create is disabled until a name is typed`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        rule.onNodeWithText("Create event").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").assertIsEnabled()
    }

    @Test
    fun `tapping create submits the typed name`() {
        var created: String? = null
        rule.setContent { StatusScreen(UiState.CreateEvent(), onCreateEvent = { n, _ -> created = n }) }

        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()
        assertEquals("My Party", created)
    }

    @Test
    fun `the create screen shows the start row defaulting to now`() {
        rule.setContent {
            StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff())
        }
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()
        rule.onNodeWithContentDescription("Edit start date").assertExists()
    }

    @Test
    fun `tapping create submits the typed name AND the chosen start`() {
        var createdName: String? = null
        var createdStart: LocalDateTime? = null
        rule.setContent {
            StatusScreen(
                UiState.CreateEvent(),
                onCreateEvent = { n, s -> createdName = n; createdStart = s },
                cutoff = fixedCutoff(),
            )
        }
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()

        assertEquals("My Party", createdName)
        // The default start is "now" — as a LOCAL wall-clock value. The container converts it; the screen
        // never touches a clock, a timezone, or a cutoff string.
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdStart)
    }

    @Test
    fun `the start default is frozen at first composition, not re-derived at submit`() {
        // The label is the screen's whole statement about what will be sent. A start that silently drifted
        // between being displayed and being posted would make the screen lie.
        val clock = MovableClock(Instant.parse("2026-07-06T12:00:00Z"))
        var createdStart: LocalDateTime? = null
        rule.setContent {
            StatusScreen(
                UiState.CreateEvent(),
                onCreateEvent = { _, s -> createdStart = s },
                cutoff = SystemCutoffFormatter(clock = clock, zone = TimeZone.UTC),
            )
        }
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()

        // Ten minutes pass while the user types.
        clock.instant = Instant.parse("2026-07-06T12:10:00Z")
        rule.onNode(hasSetTextAction()).performTextInput("My Party")
        rule.onNodeWithText("Create event").performClick()

        // The label said 12:00 and 12:00 is what was sent — NOT the instant Create was tapped.
        rule.onNodeWithText("Starts 6 Jul 2026, 12:00").assertExists()
        assertEquals(LocalDateTime(2026, 7, 6, 12, 0), createdStart)
    }

    @Test
    fun `the edit affordance opens ONE dialog carrying both the calendar and the time`() {
        // The picker is a single dialog now (calendar + [HH]:[MM] readout), not the old two-step
        // date -> Next -> time -> OK flow. One OK commits both.
        rule.setContent { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }
        rule.onNodeWithText("OK").assertDoesNotExist() // no dialog yet

        rule.onNodeWithContentDescription("Edit start date").performClick()

        rule.onNodeWithText("OK").assertExists()
        rule.onNodeWithText("Next").assertDoesNotExist() // the two-step flow is gone
        rule.onNodeWithText("12:00").assertExists() // the time readout sits under the calendar
    }

    @Test
    fun `tapping the time readout swaps the calendar for the dial and Back returns`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), cutoff = fixedCutoff()) }
        rule.onNodeWithContentDescription("Edit start date").performClick()
        rule.onNodeWithText("Cancel").assertExists() // calendar view

        rule.onNodeWithContentDescription("Edit time").performClick()

        // The dial replaced the calendar; Back (not Cancel) returns without discarding the picked date.
        rule.onNodeWithText("Back").assertExists()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        rule.onNodeWithText("12:00").assertExists() // the readout stays visible while editing

        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `the name field caps at 100 characters`() {
        rule.setContent { StatusScreen(UiState.CreateEvent()) }

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("a".repeat(100))
        field.performTextInput("b")
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(100, text.length)
    }

    @Test
    fun `create layer shows no sync line, leave, or invite`() {
        rule.setContent { StatusScreen(UiState.CreateEvent(), inviteUrl = SAMPLE_INVITE) }

        rule.onNodeWithText("In sync").assertDoesNotExist()
        rule.onNodeWithText("Synchronization", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").assertDoesNotExist()
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
    }

    @Test
    fun `creating event shows a preparing indicator and no input`() {
        rule.setContent { StatusScreen(UiState.CreatingEvent) }

        rule.onNodeWithText("Creating your event …").assertExists()
        rule.onNode(hasAnyProgressIndication()).assertExists()
        rule.onNodeWithText("Event name").assertDoesNotExist()
    }

    // ---- joined layer: status line ----

    @Test
    fun `in sync shows the settled line and no counts`() {
        rule.setContent { StatusScreen(inSync) }

        rule.onNodeWithText("In sync").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with an in-flight arrow reads ongoing`() {
        rule.setContent { StatusScreen(syncing) }

        rule.onNodeWithText("Synchronization ongoing…").assertExists()
        rule.onNodeWithText("images synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `syncing with a static arrow reads pending`() {
        rule.setContent { StatusScreen(syncPending) }

        rule.onNodeWithText("Synchronization pending…").assertExists()
        rule.onNodeWithText("Synchronization ongoing…").assertDoesNotExist()
    }

    @Test
    fun `needs-access not-determined shows the allow copy and taps request permission`() {
        var requests = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED)),
                onRequestPermission = { requests++ },
            )
        }

        rule.onNodeWithText("Allow photo access").assertExists()
        rule.onNodeWithText("Allow photo access").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `needs-access denied shows the settings copy and taps open settings`() {
        var settingsOpens = 0
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                onOpenSettings = { settingsOpens++ },
            )
        }

        rule.onNodeWithText("Turn on full access in Settings").assertExists()
        rule.onNodeWithText("Turn on full access in Settings").performClick()
        assertEquals(1, settingsOpens)
    }

    // ---- joined layer: name, leave, invite ----

    @Test
    fun `joined shows the event name as the title`() {
        rule.setContent { StatusScreen(inSync, eventName = "Anna's Birthday") }
        rule.onNodeWithText("Anna's Birthday").assertExists()
    }

    @Test
    fun `joined shows the leave action`() {
        rule.setContent { StatusScreen(inSync) }
        rule.onNodeWithContentDescription("Leave event").assertExists()
    }

    @Test
    fun `needs-access still shows leave and invite (sharing needs no access)`() {
        rule.setContent {
            StatusScreen(
                joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
                inviteUrl = SAMPLE_INVITE,
            )
        }
        rule.onNodeWithContentDescription("Leave event").assertExists()
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `activating leave shows the leave-this-event dialog`() {
        rule.setContent { StatusScreen(inSync) }

        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave this event?").assertExists()
    }

    @Test
    fun `confirming leave invokes the callback`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, leaves)
    }

    @Test
    fun `staying does not invoke leave and dismisses the dialog`() {
        var leaves = 0
        rule.setContent { StatusScreen(inSync, onLeaveEvent = { leaves++ }) }

        rule.onNodeWithContentDescription("Leave event").performClick()
        rule.onNodeWithText("Stay").performClick()
        assertEquals(0, leaves)
        rule.onNodeWithText("Leave this event?").assertDoesNotExist()
    }

    @Test
    fun `joined shows the invite QR and share action`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithText("Scan to join this event").assertExists()
        rule.onNodeWithContentDescription("Share invite link").assertExists()
    }

    @Test
    fun `joined without an invite url hides the invite affordances`() {
        rule.setContent { StatusScreen(inSync, inviteUrl = null) }
        rule.onNodeWithText("Scan to join this event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Share invite link").assertDoesNotExist()
    }

    @Test
    fun `activating share invokes the callback`() {
        var shares = 0
        rule.setContent { StatusScreen(inSync, onShareInvite = { shares++ }, inviteUrl = SAMPLE_INVITE) }
        rule.onNodeWithContentDescription("Share invite link").performClick()
        assertEquals(1, shares)
    }

    private fun hasAnyProgressIndication(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}

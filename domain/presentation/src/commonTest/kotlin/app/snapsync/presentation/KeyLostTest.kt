@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.MintsIdentifiedKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.format.InviteLinkText
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.testWithInternalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A device that lost the joined event's key (capabilities `sync-status`, `join-event`): the status line asks for the
 * invite, in its place in the priority, and reopening the event's own invite offers its key back — only while it is
 * lost, and only an invite that carries one.
 */
class KeyLostTest {

    private class Sync : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(
            SyncStatus.Ready(SyncProgress(0, 0, 0, 0, false, null)),
        )
    }

    private fun TestScope.host(
        presence: KeyPresence,
        config: EventConfig = ENCRYPTED,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        restored: MutableList<EventKey> = mutableListOf(),
    ) = StatusContainerHost(
        StatusSources(
            Sync(),
            MutableStateFlow(permission),
            MutableStateFlow(config),
            eventKey = EventKeyView(presence = MutableStateFlow<KeyPresence>(presence)),
        ),
        backgroundScope,
        queries = noQueries,
        commands = object : UserCommands by TestCommands() {
            override fun restoreKey(key: EventKey): RequestId {
                restored += key
                return RequestId(0L)
            }
        },
        cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
        joinDetails = unanswered,
        diagnostics = testDiagnostics(),
        reduction = Dispatchers.Default,
    )

    private suspend fun TestScope.driving(host: StatusContainerHost, block: suspend () -> Unit) =
        host.testWithInternalState(this) {
            runOnCreate()
            runCurrent()
            block()
            cancelAndIgnoreRemainingItems()
        }

    private fun StatusContainerHost.health(): SyncHealth = (screen.value.layer as Layer.Joined).health

    @Test
    fun `a lost key is the status line`() = runTest {
        val host = host(KeyPresence.Lost)
        driving(host) { assertEquals(SyncHealth.KeyLost, host.health()) }
    }

    @Test
    fun `a lost key outranks missing access`() = runTest {
        val host = host(KeyPresence.Lost, permission = GalleryAccess.DENIED)
        driving(host) { assertEquals(SyncHealth.KeyLost, host.health()) }
    }

    @Test
    fun `neither sharing nor receiving outranks a lost key`() = runTest {
        val host = host(KeyPresence.Lost, config = ENCRYPTED.copy(direction = Direction.Neither))
        driving(host) { assertEquals(SyncHealth.Inactive, host.health()) }
    }

    @Test
    fun `a key that cannot be read for now is never shown as lost`() = runTest {
        val host = host(KeyPresence.Unknown)
        driving(host) { assertEquals(SyncHealth.InSync, host.health()) }
    }

    @Test
    fun `reopening the event's whole invite offers its key back while it is lost`() = runTest {
        val restored = mutableListOf<EventKey>()
        val host = host(KeyPresence.Lost, restored = restored)
        driving(host) {
            host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT), LINK_KEY)))
            runCurrent()
            assertEquals(listOf(LINK_KEY), restored)
            assertEquals(null, (host.screen.value.layer as Layer.Joined).pendingSwitch, "no join screen")
        }
    }

    @Test
    fun `an invite carrying another key is offered and refused and the key stays lost`() = runTest {
        val restored = mutableListOf<EventKey>()
        val host = host(KeyPresence.Lost, restored = restored)
        driving(host) {
            host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT), LINK_KEY)))
            runCurrent()
            assertEquals(
                listOf(LINK_KEY),
                restored,
                "the core is asked: it keeps only the event's own key, so the key stays lost",
            )
            assertEquals(SyncHealth.KeyLost, host.health())
        }
    }

    @Test
    fun `an invite without its key offers nothing`() = runTest {
        val restored = mutableListOf<EventKey>()
        val host = host(KeyPresence.Lost, restored = restored)
        driving(host) {
            host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT), null)))
            runCurrent()
            assertEquals(emptyList(), restored)
            assertEquals(SyncHealth.KeyLost, host.health())
        }
    }

    @Test
    fun `a member who holds the key rescans and nothing changes`() = runTest {
        val restored = mutableListOf<EventKey>()
        val host = host(KeyPresence.Held(HELD), restored = restored)
        driving(host) {
            host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT), LINK_KEY)))
            runCurrent()
            assertEquals(emptyList(), restored)
        }
    }

    @Test
    fun `no invite is offered without its key and a shown QR closes`() = runTest {
        val shared = mutableListOf<String>()
        val presence = MutableStateFlow<KeyPresence>(KeyPresence.Held(HELD))
        val host = StatusContainerHost(
            StatusSources(
                Sync(),
                MutableStateFlow(GalleryAccess.GRANTED),
                MutableStateFlow(ENCRYPTED),
                eventKey = EventKeyView(presence = presence),
            ),
            backgroundScope,
            queries = noQueries,
            commands = sharingCommands { url -> shared += url },
            cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
            joinDetails = unanswered,
            diagnostics = testDiagnostics(),
            reduction = Dispatchers.Default,
        )
        driving(host) {
            val joined = { host.screen.value.layer as Layer.Joined }
            assertEquals(
                InviteLinkText.format(InviteLink(testEventId(EVENT), LINK_KEY)),
                joined().inviteUrl,
                "the whole invite",
            )
            host.surfaces.onQrOpen()
            runCurrent()
            presence.value = KeyPresence.Unknown
            runCurrent()
            assertNull(joined().inviteUrl, "an encrypted event's invite is never offered without its key")
            assertFalse(host.screen.value.overlays.showingQr, "a shown QR closes")
            host.onShareInvite()
            runCurrent()
            assertEquals(emptyList(), shared, "nothing is shared")
        }
    }

    /** The inert bundle, with [share] for the share sheet. */
    private fun sharingCommands(share: (String) -> Unit) = TestCommands(share = { url, _ -> share(url) })

    private companion object {
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        val NOW: Instant = Instant.parse("2026-07-09T12:00:00Z")

        @OptIn(MakesEventKey::class)
        val LINK_KEY = EventKey.of(ByteArray(EventKey.LENGTH) { it.toByte() })!!

        @OptIn(MintsIdentifiedKey::class)
        val HELD = IdentifiedKey(LINK_KEY, EventKeyId("0011223344556677"))
        val ENCRYPTED = EventConfig(
            testEventId(EVENT),
            "Party",
            CaptureCutoff(TimestampText.parse("2026-07-06T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T00:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
            keyId = "0123456789abcdef",
        )
    }
}

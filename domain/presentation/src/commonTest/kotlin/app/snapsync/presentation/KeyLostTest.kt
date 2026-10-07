@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.GalleryAccess
import app.snapsync.model.KeyPresence
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.encodeEventKey
import app.snapsync.model.encodeEventUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.testWithInternalState

/**
 * A device that lost the joined event's key (capabilities `sync-status`, `join-event`): the status line asks for the
 * invite, in its place in the priority, and reopening the event's own invite offers its key back — only while it is
 * lost, and only an invite that carries one.
 */
class KeyLostTest {

    private class Sync : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Ready(SyncProgress(0, 0, 0, 0, false, null)))
    }

    private fun TestScope.host(
        presence: KeyPresence,
        config: EventConfig = ENCRYPTED,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        restored: MutableList<String> = mutableListOf(),
    ) = StatusContainerHost(
        StatusSources(
            Sync(),
            MutableStateFlow(permission),
            MutableStateFlow(config),
            eventKey = EventKeyView(presence = MutableStateFlow(presence)),
        ),
        backgroundScope,
        queries = noQueries,
        commands = restoringCommands { key -> restored += key; true },
        cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
        diagnostics = testDiagnostics(),
    )

    /** The inert bundle, with [restore] for the reopened invite's key. */
    private fun restoringCommands(restore: suspend (String) -> Boolean) = testCommands().let {
        UserCommands(
            it.leave, it.create, it.commitJoin, it.share, it.requestAccess, it.openSettings, it.openLink, it.choosePhotos,
            it.reconfigure, it.rename, it.resetRename, it.sendDiagnostics, it.setMobileData, restore,
        )
    }

    private suspend fun TestScope.driving(host: StatusContainerHost, block: suspend () -> Unit) =
        host.testWithInternalState(this) {
            runOnCreate()
            runCurrent()
            block()
            cancelAndIgnoreRemainingItems()
        }

    private fun StatusContainerHost.health(): SyncHealth = (container.stateFlow.value.layer as Layer.Joined).health

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
        val restored = mutableListOf<String>()
        val host = host(KeyPresence.Lost, restored = restored)
        driving(host) {
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT, key = LINK_KEY)))
            runCurrent()
            assertEquals(listOf(LINK_KEY), restored)
            assertEquals(null, (host.container.stateFlow.value.layer as Layer.Joined).pendingSwitch, "no join screen")
        }
    }

    @Test
    fun `an invite without its key offers nothing`() = runTest {
        val restored = mutableListOf<String>()
        val host = host(KeyPresence.Lost, restored = restored)
        driving(host) {
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT)))
            runCurrent()
            assertEquals(emptyList(), restored)
            assertEquals(SyncHealth.KeyLost, host.health())
        }
    }

    @Test
    fun `a member who holds the key rescans and nothing changes`() = runTest {
        val restored = mutableListOf<String>()
        val host = host(KeyPresence.Held, restored = restored)
        driving(host) {
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT, key = LINK_KEY)))
            runCurrent()
            assertEquals(emptyList(), restored)
        }
    }

    @Test
    fun `no invite is offered without its key and a shown QR closes`() = runTest {
        val shared = mutableListOf<String>()
        val inviteKey = MutableStateFlow<String?>(LINK_KEY)
        val host = StatusContainerHost(
            StatusSources(
                Sync(), MutableStateFlow(GalleryAccess.GRANTED), MutableStateFlow(ENCRYPTED),
                eventKey = EventKeyView(inviteKey = inviteKey, presence = MutableStateFlow(KeyPresence.Held)),
            ),
            backgroundScope,
            queries = noQueries,
            commands = sharingCommands { url -> shared += url },
            cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
            diagnostics = testDiagnostics(),
        )
        driving(host) {
            val joined = { host.container.stateFlow.value.layer as Layer.Joined }
            assertEquals(encodeEventUrl(EventLinkPayload(EVENT, key = LINK_KEY)), joined().inviteUrl, "the whole invite")
            host.surfaces.onQrOpen()
            runCurrent()
            inviteKey.value = null
            runCurrent()
            assertNull(joined().inviteUrl, "an encrypted event's invite is never offered without its key")
            assertFalse(host.container.stateFlow.value.overlays.showingQr, "a shown QR closes")
            host.onShareInvite()
            runCurrent()
            assertEquals(emptyList(), shared, "nothing is shared")
        }
    }

    /** The inert bundle, with [share] for the share sheet. */
    private fun sharingCommands(share: (String) -> Unit) = testCommands().let {
        UserCommands(
            it.leave, it.create, it.commitJoin, { url, _ -> share(url) }, it.requestAccess, it.openSettings, it.openLink,
            it.choosePhotos, it.reconfigure, it.rename, it.resetRename, it.sendDiagnostics, it.setMobileData, it.restoreEventKey,
        )
    }

    private companion object {
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        val NOW: Instant = Instant.parse("2026-07-09T12:00:00Z")
        val LINK_KEY = encodeEventKey(ByteArray(32) { it.toByte() })
        val ENCRYPTED = EventConfig(
            EVENT, "Party", captureCutoff("2026-07-06T00:00:00Z"), maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
            endsAt = eventEnd("2099-12-31T00:00:00Z"), deletesAt = deletesAt("2099-12-31T00:00:00Z"),
            keyId = "0123456789abcdef",
        )
    }
}

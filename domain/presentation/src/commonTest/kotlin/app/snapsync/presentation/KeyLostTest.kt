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
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.query.encryption.InviteRead
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

    private fun Sync(): StateFlow<SharingProgressRead> = MutableStateFlow(
        SharingProgressRead.Read(SharingProgress(shared = 0, total = 0, handedOver = 0)),
    )

    private fun TestScope.host(
        presence: KeyPresence,
        config: TestMembership = ENCRYPTED,
        permission: PhotoGrant = PhotoGrant.FULL,
        restored: MutableList<EventKey> = mutableListOf(),
    ) = testHost(
        testQueries(
            Sync(),
            MutableStateFlow(permission),
            MutableStateFlow(config).held(),
            eventKey = testKeys(presence = MutableStateFlow<KeyPresence>(presence)),
            network = AlwaysOnline,
            mobileData = AlwaysAllowed,
        ),
        backgroundScope,
        shareableCount = noQueries,
        commands = TestCommands().apply {
            restoreKey = { key ->
                restored += key
                null
            }
        }.door,
        cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
        joinDetails = unanswered,
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
        val host = host(KeyPresence.Lost, permission = PhotoGrant.REFUSED)
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
            host.onInvite(invited(testEventId(EVENT), LINK_KEY))
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
            host.onInvite(invited(testEventId(EVENT), LINK_KEY))
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
            host.onInvite(invited(testEventId(EVENT), null))
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
            host.onInvite(invited(testEventId(EVENT), LINK_KEY))
            runCurrent()
            assertEquals(emptyList(), restored)
        }
    }

    @Test
    fun `no invite is offered without its key and a shown QR closes`() = runTest {
        val shared = mutableListOf<String>()
        val presence = MutableStateFlow<KeyPresence>(KeyPresence.Held(HELD))
        val host = testHost(
            testQueries(
                Sync(),
                MutableStateFlow(PhotoGrant.FULL),
                MutableStateFlow(ENCRYPTED).held(),
                // The invite as the query reads it: whole while the key is held, none without it.
                eventKey = testKeys(
                    presence = presence,
                    invite = object : InviteRead {
                        override fun invite(): String? = (presence.value as? KeyPresence.Held)?.let {
                            inviteText(testEventId(EVENT), it.key.key)
                        }
                    },
                ),
                network = AlwaysOnline,
                mobileData = AlwaysAllowed,
            ),
            backgroundScope,
            shareableCount = noQueries,
            commands = sharingCommands { url -> shared += url }.door,
            cutoffFormatter = CutoffFormatter(now = { NOW }, zone = TimeZone.UTC),
            joinDetails = unanswered,
            reduction = Dispatchers.Default,
        )
        driving(host) {
            val joined = { host.screen.value.layer as Layer.Joined }
            assertEquals(
                inviteText(testEventId(EVENT), LINK_KEY),
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
        val ENCRYPTED = TestMembership(
            testEventId(EVENT),
            "Party",
            CaptureCutoff(timestamp("2026-07-06T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T00:00:00Z")),
            endsAt = EventEnd(timestamp("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
            keyId = "0123456789abcdef",
        )
    }
}

package app.snapsync.feature.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testClock
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.testShared
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.EventConfig
import app.snapsync.model.PauseReason
import app.snapsync.model.SelectionRule
import app.snapsync.model.SuppressionReadiness
import app.snapsync.model.TimestampText
import app.snapsync.ports.Clock
import app.snapsync.ports.DbOpen
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.downloads.RecognitionSource
import app.snapsync.services.downloads.SuppressionSource
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.LedgerService
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The entry-gate translation (capability `background-upload`, "The upload cycle owns its entry decision"; `join-event`,
 * "An unreadable config is not an absent config") over the REAL ledger, membership and identity services on the ports'
 * in-memory mocks: which reads it makes, how each failure rolls up into the skip, and that only an admitted, joined
 * cycle asks the echo-suppression store.
 */
class CycleGateReadTest {

    private val host = "https://edge.example"
    private val cutoff = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z"))

    /** The suppression store, scripted; records whether the gate asked it, and hands out [suppressed]. */
    private class Suppression(
        var readiness: SuppressionReadiness = SuppressionReadiness.Ready,
        val suppressed: Set<AssetId> = emptySet(),
        var recognised: Boolean = true,
    ) : SuppressionSource, RecognitionSource {
        var readinessAsked = 0
        var idsAsked = 0
        override suspend fun readiness(): SuppressionReadiness = readiness.also { readinessAsked++ }
        override suspend fun suppressedLocalIds(): Set<AssetId> = suppressed.also { idsAsked++ }
        override suspend fun isRecognised(eventId: EventId): Boolean = recognised
    }

    private fun event(eventId: String = "E", saveToAlbum: Boolean = false, keyId: String? = null) = EventConfig(
        testEventId(eventId),
        "E",
        cutoff,
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2099-01-01T00:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
        saveToAlbum = saveToAlbum,
        keyId = keyId,
    )

    private fun gate(
        joined: EventConfig? = event(),
        store: SharedStore = SharedStore(),
        records: SharedDatabase = store.db,
        ledger: LedgerService = TestLedger().service,
        identity: PersistedDeviceIdentity = testIdentity("11111111-1111-4111-8111-111111111111"),
        suppression: Suppression = Suppression(),
        admission: UploadAdmission = UploadAdmission.Admit,
        albumExclusions: suspend (CaptureCutoff) -> Set<AssetId> = { emptySet() },
        eventKeys: EventKeys = EventKeys(fakeCrypto(), inMemorySecureStore()),
        clock: Clock = testClock(),
    ) = CycleGateRead(
        records = records,
        ledger = ledger,
        config = configService(joined, store, clock),
        identity = identity,
        suppression = suppression,
        recognition = suppression,
        host = host,
        admission = { admission },
        eventKeys = eventKeys,
        albumExclusions = albumExclusions,
    )

    // ---- a lost event key -------------------------------------------------------------------------------

    @Test
    fun `an encrypted event whose key this device lost is withheld however the process would admit`() = runTest {
        val suppression = Suppression()
        val gate = gate(joined = event(keyId = "lost-key"), suppression = suppression).read()
        assertEquals(testEventId("E"), assertIs<CycleGate.Withheld>(gate).config.eventId)
        assertEquals(0, suppression.readinessAsked, "a withheld cycle never asks the suppression store")
    }

    @Test
    fun `an event whose photos are deleted is withheld however the process would admit`() = runTest {
        val gate = gate(joined = event().copy(deleted = true)).read()
        assertEquals(testEventId("E"), assertIs<CycleGate.Withheld>(gate).config.eventId)
    }

    // ---- joined -----------------------------------------------------------------------------------------

    @Test
    fun `a joined admitted cycle runs with the ledger's manifest version and the membership's album choice`() =
        runTest {
            val ledger = TestLedger().service.apply {
                bumpManifestVersion()
                bumpManifestVersion()
            }
            val suppression = Suppression()

            val read = gate(joined = event("E", saveToAlbum = true), ledger = ledger, suppression = suppression).read()

            val run = assertIs<CycleGate.Run>(read)
            assertEquals(testEventId("E"), run.config.eventId)
            assertEquals(host, run.config.host)
            assertEquals(testEventId("E"), run.membership.eventId)
            assertTrue(run.membership.saveToAlbum)
            assertEquals(ledger.manifestVersion(), run.membership.manifestVersion)
            assertEquals(1, suppression.readinessAsked, "an admitted cycle asks the suppression store once")
            assertEquals(0, suppression.idsAsked, "the policy is a supplier: the gate builds none")
        }

    @Test
    fun `the membership's policy reads the suppressed ids and the album exclusions from the cutoff`() = runTest {
        val suppression = Suppression(suppressed = setOf(AssetId("echo")))
        val askedFrom = mutableListOf<CaptureCutoff>()

        val run = assertIs<CycleGate.Run>(
            gate(suppression = suppression, albumExclusions = {
                askedFrom += it
                setOf(AssetId("meme"))
            }).read(),
        )
        val rules = run.membership.policy().rules

        assertTrue(SelectionRule.NotEcho(setOf(AssetId("echo"))) in rules, "$rules")
        assertTrue(SelectionRule.NotInDenylistedAlbum(setOf(AssetId("meme"))) in rules, "$rules")
        assertTrue(SelectionRule.CaptureAfter(cutoff) in rules, "the floor is the membership's own cutoff")
        assertEquals(listOf(cutoff), askedFrom)
        assertEquals(1, suppression.idsAsked)
    }

    @Test
    fun `before the event starts the floor is the start — from the start on it is the member's own`() = runTest {
        // Capability `photo-sharing`, "Nothing is shared before the event starts": a member who chose to share from two
        // days before the start shares nothing of those days until the start has passed — then all of them.
        val start = EventStart(TimestampText.parse("2026-07-10T18:00:00Z"))
        val own = CaptureCutoff(TimestampText.parse("2026-07-08T18:00:00Z"))
        val joined = event().copy(
            minPhotoDate = own,
            startsAt = start,
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-12T18:00:00Z")),
        )
        var now = Instant.parse("2026-07-09T12:00:00Z")
        val clock = object : Clock {
            override fun now(): Instant = now
            override fun timeZone(): TimeZone = TimeZone.UTC
        }
        val run = assertIs<CycleGate.Run>(gate(joined = joined, clock = clock).read())

        val beforeTheStart = run.membership.policy().rules
        assertTrue(SelectionRule.CaptureAfter(CaptureCutoff(start.at)) in beforeTheStart, "before the start")
        now = Instant.parse("2026-07-10T18:00:01Z")
        assertTrue(SelectionRule.CaptureAfter(own) in run.membership.policy().rules, "from the start on")
    }

    @Test
    fun `a membership whose received photos are not recognised yet is withheld - creating and publishing nothing`() =
        runTest {
            val read = gate(suppression = Suppression(recognised = false)).read()

            assertEquals(testEventId("E"), assertIs<CycleGate.Withheld>(read).config.eventId)
        }

    @Test
    fun `an old suppression store pauses an admitted cycle`() = runTest {
        val read = gate(suppression = Suppression(SuppressionReadiness.OldSchema)).read()

        assertEquals(CycleGate.Paused(PauseReason.OLD_SCHEMA), read)
    }

    @Test
    fun `an unopenable suppression store skips an admitted cycle`() = runTest {
        val read = gate(suppression = Suppression(SuppressionReadiness.Unavailable("locked"))).read()

        assertEquals(CycleGate.Skip("echo-suppression store unavailable (locked)"), read)
    }

    @Test
    fun `a process that may not create withholds and never opens the suppression store`() = runTest {
        val suppression = Suppression(SuppressionReadiness.Unavailable("never asked"))

        val read = gate(suppression = suppression, admission = UploadAdmission.Withheld).read()

        assertEquals(testEventId("E"), assertIs<CycleGate.Withheld>(read).config.eventId)
        assertEquals(0, suppression.readinessAsked)
    }

    // ---- not joined -------------------------------------------------------------------------------------

    @Test
    fun `no membership is not joined and asks nothing of the suppression store`() = runTest {
        val suppression = Suppression()

        assertEquals(CycleGate.NotJoined, gate(joined = null, suppression = suppression).read())
        assertEquals(0, suppression.readinessAsked)
    }

    // ---- could not look ---------------------------------------------------------------------------------

    @Test
    fun `records still in their legacy homes pause the cycle before anything is read`() = runTest {
        // The extension's view after an update, before the app copied the records: nothing marked as moved.
        val pending = SharedDatabase(inMemoryDatabases(), legacy = null)

        assertEquals(CycleGate.Paused(PauseReason.LEGACY_RECORDS), gate(records = pending).read())
    }

    @Test
    fun `an unreadable membership skips naming the config read`() = runTest {
        val store = SharedStore().apply { membershipUnreadable() }

        val skip = assertIs<CycleGate.Skip>(gate(store = store).read())

        assertTrue(skip.detail.startsWith("protected data unavailable (config: "), skip.detail)
        assertFalse(skip.detail.startsWith("protected data unavailable (config: null"), "the read's detail is named")
        assertTrue(skip.detail.endsWith(", deviceId readable=true)"), skip.detail)
    }

    @Test
    fun `a locked identity store skips naming the identity as unreadable`() = runTest {
        val skip = assertIs<CycleGate.Skip>(gate(identity = unreadableIdentity()).read())

        assertTrue(
            skip.detail.startsWith("protected data unavailable (config: null, deviceId readable=false"),
            skip.detail,
        )
        assertTrue(", deviceId unreadable (" in skip.detail, skip.detail)
        assertFalse("absent" in skip.detail, skip.detail)
    }

    @Test
    fun `an identity this process may not mint skips naming it absent`() = runTest {
        val readOnly = PersistedDeviceIdentity(
            DeviceIdentityRole.READ_ONLY,
            inMemorySecureStore(),
            PlatformDeviceId { "never minted" },
        )

        val skip = assertIs<CycleGate.Skip>(gate(identity = readOnly).read())

        assertEquals(
            "protected data unavailable (config: null, deviceId readable=false, deviceId absent and unmintable here)",
            skip.detail,
        )
    }

    @Test
    fun `any other identity failure propagates rather than becoming a skip`() = runTest {
        val broken = PersistedDeviceIdentity(
            DeviceIdentityRole.MINTING,
            inMemorySecureStore(),
            PlatformDeviceId { throw IllegalStateException("platform id fault") },
        )

        val thrown = assertFailsWith<IllegalStateException> { gate(identity = broken).read() }
        assertEquals("platform id fault", thrown.message)
    }

    @Test
    fun `an unreadable ledger skips naming the manifest version`() = runTest {
        val databases = inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked")))
        val locked = LedgerService(testShared(databases)) { testEventId("E") }
        val suppression = Suppression()

        val skip = assertIs<CycleGate.Skip>(gate(ledger = locked, suppression = suppression).read())

        assertTrue(
            skip.detail.startsWith("protected data unavailable (config: null, deviceId readable=true"),
            skip.detail,
        )
        assertTrue(", manifest version unreadable (" in skip.detail, skip.detail)
        assertEquals(0, suppression.readinessAsked, "a skipped cycle opens nothing more")
    }
}

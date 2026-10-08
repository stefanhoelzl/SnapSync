package app.snapsync.feature.upload

import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.membershipUnreadable
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.EventConfig
import app.snapsync.model.PauseReason
import app.snapsync.model.SelectionRule
import app.snapsync.model.SuppressionReadiness
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.ports.DbOpen
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.downloads.SuppressionSource
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.LedgerService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The entry-gate translation (capability `background-upload`, "The upload cycle owns its entry decision"; `join-event`,
 * "An unreadable config is not an absent config") over the REAL ledger, membership and identity services on the ports'
 * in-memory mocks: which reads it makes, how each failure rolls up into the skip, and that only an admitted, joined
 * cycle asks the echo-suppression store.
 */
class CycleGateReadTest {

    private val host = "https://edge.example"
    private val cutoff = captureCutoff("2026-01-01T00:00:00Z")

    /** The suppression store, scripted; records whether the gate asked it, and hands out [suppressed]. */
    private class Suppression(
        var readiness: SuppressionReadiness = SuppressionReadiness.Ready,
        val suppressed: Set<AssetId> = emptySet(),
    ) : SuppressionSource {
        var readinessAsked = 0
        var idsAsked = 0
        override suspend fun readiness(): SuppressionReadiness = readiness.also { readinessAsked++ }
        override suspend fun suppressedLocalIds(): Set<AssetId> = suppressed.also { idsAsked++ }
    }

    private fun event(eventId: String = "E", saveToAlbum: Boolean = false, keyId: String? = null) = EventConfig(
        eventId,
        "E",
        cutoff,
        maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"),
        endsAt = eventEnd("2099-12-31T00:00:00Z"),
        deletesAt = deletesAt("2099-12-31T00:00:00Z"),
        saveToAlbum = saveToAlbum,
        keyId = keyId,
    )

    private fun gate(
        joined: EventConfig? = event(),
        files: RecordingFiles = RecordingFiles(),
        ledger: LedgerService = TestLedger().service,
        identity: PersistedDeviceIdentity = testIdentity("11111111-1111-4111-8111-111111111111"),
        suppression: SuppressionSource = Suppression(),
        admission: UploadAdmission = UploadAdmission.Admit,
        albumExclusions: suspend (CaptureCutoff) -> Set<AssetId> = { emptySet() },
        eventKeys: EventKeys = EventKeys(fakeCrypto(), inMemorySecureStore()),
    ) = CycleGateRead(
        ledger = ledger,
        config = configService(joined, files),
        identity = identity,
        suppression = suppression,
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
        assertEquals("E", assertIs<CycleGate.Withheld>(gate).config.eventId)
        assertEquals(0, suppression.readinessAsked, "a withheld cycle never asks the suppression store")
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
            assertEquals("E", run.config.eventId)
            assertEquals(host, run.config.host)
            assertEquals("E", run.membership.eventId)
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

        assertEquals("E", assertIs<CycleGate.Withheld>(read).config.eventId)
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
    fun `an unreadable membership file skips naming the config read`() = runTest {
        val files = RecordingFiles().apply { membershipUnreadable() }

        val skip = assertIs<CycleGate.Skip>(gate(files = files).read())

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
        val locked = LedgerService(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked")))) { "E" }
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

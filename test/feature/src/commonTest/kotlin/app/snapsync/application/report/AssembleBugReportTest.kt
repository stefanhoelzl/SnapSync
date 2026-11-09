package app.snapsync.application.report

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.logs.DeviceLogTails
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.report.DeviceConditionsRead
import app.snapsync.data.report.EnvironmentRead
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.trust.PresentedCertificate
import app.snapsync.data.trust.PresentedChain
import app.snapsync.data.trust.RefusalFacts
import app.snapsync.data.trust.RefusalRead
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.UploadProgress
import app.snapsync.data.upload.UploaderAdmission
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.report.ReportedEnvironment
import app.snapsync.domain.report.StandbyBucket
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.UploadAdmission
import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.feature.support.NothingWithheld
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.membershipIn
import app.snapsync.feature.support.receivingCounts
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.format.DeviceLogText
import app.snapsync.format.LegacyMembership
import app.snapsync.format.TimestampText
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DevControlsMock
import app.snapsync.mock.DeviceConditionsMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.ProcessInfoMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryExtensionRegistry
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryNetworkMonitor
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.FileArea
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.testids.testEventId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The bug report's assembly (capability `privacy-security`), against the honest in-memory doubles.
 *
 * The budget is the load-bearing property: an over-budget report is rejected by the reporting server and swallowed by
 * the SDK, so it arrives nowhere and says nothing — the one failure mode the device cannot observe. Every case here is
 * really about that bound holding.
 */
@OptIn(EntryPointCall::class)
class AssembleBugReportTest {

    private val budget = 1_000

    /** What the user wrote — already trimmed and bounded by the sheet before it reaches here. */
    private val NOTE = "photos stopped arriving after I rejoined"

    /** The surface the report was written from — an opaque label the UI supplies. */
    private val SCREEN = ReportContext("Joined")

    private val DEVICE = "A1B2C3D4-0000-4000-8000-000000000001"

    private val UNKNOWN = environment(uploaders = "unknown")

    private fun environment(
        appVersion: String = "unknown",
        buildNumber: String = "unknown",
        osVersion: String = "unknown",
        deviceModel: String = "unknown",
        uploaders: String = "app+extension",
        uploadBase: String = "https://snapsync.stho.net/api/v2",
    ) = ReportedEnvironment(
        BuildLabel(appVersion, buildNumber),
        osVersion,
        deviceModel,
        uploaders,
        uploadBase,
        "production",
    )

    private fun logLines(prefix: String, count: Int): String =
        (1..count).joinToString("") { "$prefix line $it padded out to a realistic width ........\n" }

    /** What the latest refused attestation left, for the assembly a test builds next. */
    private var refusal: RefusalFacts? = null

    /** The device's identity — minted, as on any device that ever ran: a report itself never mints what it reports. */
    private var identity: DeviceIdentity = testIdentity(DEVICE).also { it.id() }

    /** This process's footprint, `null` where the platform reads none. */
    private var footprint: MemoryFootprint? = MemoryFootprint(142 * 1_048_576L, 160 * 1_048_576L, null)

    /** The partial grant's selection, as the gallery last published it. */
    private var selection = SelectionSnapshots()

    private fun assembly(
        appLog: String? = null,
        extLog: String? = null,
        config: LegacyMembership? = null,
        permission: LibraryGrant = LibraryGrant.GRANTED,
        ledger: TestLedger = TestLedger(),
        downloads: ReceivingCounts = receivingCounts(inMemoryDatabases()),
        environment: ReportedEnvironment = UNKNOWN,
        // `null`: a network the platform never answers for.
        network: NetworkAccess? = NetworkAccess.Online(restricted = false),
        conditions: DeviceConditions = DeviceConditionsMock().port(),
        mobileData: MobileDataStore = MobileDataStore(inMemoryPreferences()),
        deniedLogs: Set<Pair<FileArea, String>> = emptySet(),
    ): AssembleBugReport {
        val membership = membershipIn(config)
        val grant = GrantRead(MutableStateFlow(permission))
        val clock = fixedClock(Instant.parse("2026-07-06T12:00:00Z"), VIENNA)
        return AssembleBugReport(
            ids = ReportedIdsRead(membership, identity, BuildInfoMock(processId = "app.snapsync").port()),
            state = ReportState(
                environment = object : EnvironmentRead {
                    override fun environment() = environment
                },
                membership = membership,
                grant = grant,
                osUploader = OsUploader(inMemoryExtensionRegistry(), UploaderSwitches(DevControlsMock().port()), grant),
                appAdmission = object : UploaderAdmission {
                    override fun admission(): UploadAdmission = UploadAdmission.ADMIT
                },
                refusal = object : RefusalRead {
                    override fun refusalFacts(): RefusalFacts? = refusal
                },
            ),
            ledger = ReportLedger(UploadProgress(ledger.shared), downloads, membership),
            device = DeviceFacts(
                network = NetworkReadings(network?.let { inMemoryNetworkMonitor(MutableStateFlow(it)) } ?: Silent),
                mobileData = mobileData,
                conditions = DeviceConditionsRead(
                    conditions,
                    clock,
                    FootprintReading(ProcessInfoMock().also { it.memory = footprint }.port()),
                ),
                selection = selection,
                grant = grant,
            ),
            // The REAL log tails over the two log files: the app's in its private area, the extension's in the shared.
            logs = ReportLogs(
                DeviceLogTails(
                    inMemoryFiles(
                        shared = listOfNotNull(
                            extLog?.let { DeviceLogText.EXTENSION_LOG to it.encodeToByteArray() },
                        ).toMap(mutableMapOf()),
                        private = listOfNotNull(
                            appLog?.let { DeviceLogText.APP_LOG to it.encodeToByteArray() },
                        ).toMap(mutableMapOf()),
                        denied = deniedLogs,
                    ),
                ),
                budget,
            ),
        )
    }

    private fun DiagnosticDump.snapsync(): Map<String, String> = sections().getValue(CrashSections.SNAPSYNC)

    // ---- a refused phone's offered report (capability `privacy-security`) ----

    private val refused = RefusalFacts(
        DeviceRefusal.DEVICE_UNVERIFIABLE,
        detail = "certificate",
        chain = PresentedChain(
            listOf(
                PresentedCertificate(
                    "CN=Intermediate,O=OnePlus",
                    "CN=Root,O=OnePlus",
                    "2019-03-01T00:00:00Z",
                    "2029-03-01T00:00:00Z",
                    "EC 256",
                ),
                PresentedCertificate(
                    "CN=Root,O=OnePlus",
                    "CN=Root,O=OnePlus",
                    "2016-01-01T00:00:00Z",
                    "2036-01-01T00:00:00Z",
                    "RSA 4096",
                ),
            ),
            rootKeySha256 = "3c7a00ff",
        ),
    )

    private fun refuse(facts: RefusalFacts) {
        refusal = facts
    }

    @Test
    fun `an offered report carries the refused verification - which root the chain ends at`() = runTest {
        refuse(refused)
        val dump = assembly()(NOTE, SCREEN.copy(verification = true))
        assertEquals("device-unverifiable (certificate)", dump.state["attest_failure"])
        assertEquals(
            "[1] subject=CN=Intermediate,O=OnePlus issuer=CN=Root,O=OnePlus valid=2019-03-01T00:00:00Z..2029-03-01T00:00:00Z key=EC 256\n" +
                "[2] subject=CN=Root,O=OnePlus issuer=CN=Root,O=OnePlus valid=2016-01-01T00:00:00Z..2036-01-01T00:00:00Z key=RSA 4096",
            dump.state["attest_chain"],
        )
        assertEquals("3c7a00ff", dump.state["attest_root_key_sha256"])
    }

    @Test
    fun `any other report carries no verification facts`() = runTest {
        refuse(refused)
        val dump = assembly()(NOTE, SCREEN)
        assertTrue(dump.state.keys.none { it.startsWith("attest_") }, "${dump.state.keys}")
    }

    @Test
    fun `a refusal with no detail and no chain carries its reason alone`() = runTest {
        refuse(RefusalFacts(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null, chain = null))
        val dump = assembly()(NOTE, SCREEN.copy(verification = true))
        assertEquals("device-unverifiable", dump.state["attest_failure"])
        assertTrue(dump.state.keys.none { it.startsWith("attest_chain") || it.startsWith("attest_root") })
    }

    @Test
    fun `an offered report from a phone not refused says so`() = runTest {
        val dump = assembly()(NOTE, SCREEN.copy(verification = true))
        assertEquals("none recorded", dump.state["attest_failure"])
        assertTrue("attest_chain" !in dump.state)
    }

    // ---- the logs ----

    @Test
    fun `two oversized logs never exceed the budget`() = runTest {
        val dump = assembly(appLog = logLines("app", 500), extLog = logLines("ext", 500))(NOTE, SCREEN)

        assertTrue(
            dump.logBytes <= budget,
            "carried ${dump.logBytes} bytes of log against a $budget budget — an over-budget report is " +
                "rejected at ingest and silently lost",
        )
    }

    @Test
    fun `a short extension log leaves its slack to the app log`() = runTest {
        val extension = "ext one line\n"
        val dump = assembly(appLog = logLines("app", 500), extLog = extension)(NOTE, SCREEN)

        assertEquals(extension, dump.extensionLog)
        assertTrue(
            dump.appLog.encodeToByteArray().size > budget / 2,
            "the app log took only ${dump.appLog.encodeToByteArray().size} bytes: unused extension " +
                "budget must be borrowed, or the common device (extension barely runs) wastes half a report",
        )
        assertTrue(dump.logBytes <= budget)
    }

    @Test
    fun `tails begin at a line boundary`() = runTest {
        val dump = assembly(appLog = logLines("app", 500), extLog = logLines("ext", 500))(NOTE, SCREEN)

        assertTrue(dump.appLog.startsWith("app line "), "app tail began mid-line: ${dump.appLog.take(40)}")
        assertTrue(dump.extensionLog.startsWith("ext line "), "extension tail began mid-line")
    }

    @Test
    fun `a log that has never been written comes back empty`() = runTest {
        val dump = assembly(appLog = "app only\n", extLog = null)(NOTE, SCREEN)

        assertEquals("", dump.extensionLog)
        assertEquals("app only\n", dump.appLog)
    }

    @Test
    fun `a log that cannot be read says why - never that it said nothing`() = runTest {
        val dump = assembly(
            appLog = "app only\n",
            extLog = "ext\n",
            deniedLogs = setOf(FileArea.SHARED to DeviceLogText.EXTENSION_LOG),
        )(NOTE, SCREEN)

        assertTrue(dump.extensionLog.startsWith("failed (the extension log was denied"), dump.extensionLog)
        assertEquals("app only\n", dump.appLog)
    }

    // ---- the ids ----

    @Test
    fun `the report carries SnapSync's own ids in their section - and not in the state`() = runTest {
        val event = "5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e"
        val dump = assembly(config = picnic(event))(NOTE, SCREEN)

        assertEquals(event, dump.snapsync()["event"])
        assertEquals(DEVICE, dump.snapsync()["device"])
        assertEquals("app.snapsync", dump.snapsync()["process"])
        assertFalse("event_id" in dump.state || "device_id" in dump.state, "${dump.state.keys}")
    }

    @Test
    fun `an unreadable device id is written failed - the report still goes`() = runTest {
        val dump = run {
            identity = unreadableIdentity()
            assembly()(NOTE, SCREEN)
        }

        assertTrue(dump.snapsync().getValue("device").startsWith("failed (the secure store could not be read"))
        assertEquals("none", dump.snapsync()["event"])
    }

    // ---- the state ----

    @Test
    fun `the state section names the build and tier and membership`() = runTest {
        val dump = assembly(
            config = picnic(
                "5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e",
                name = "Anna's Birthday",
                direction = Direction.Both,
            ),
            permission = LibraryGrant.LIMITED,
            environment = environment(
                appVersion = "0.2",
                buildNumber = "512",
                osVersion = "iOS 26.5",
                deviceModel = "iPhone12,8",
            ),
        )(NOTE, SCREEN)

        assertEquals("512", dump.state["build"])
        assertEquals("app+extension", dump.state["uploaders_carried"])
        assertEquals("NOT_REGISTRABLE", dump.state["extension_registrable"], "the upload facts, not a single tier")
        assertEquals("ADMIT", dump.state["app_admission"])
        assertEquals(null, dump.state["upload_tier"], "both uploaders may be active: there is no single tier")
        assertEquals("https://snapsync.stho.net/api/v2", dump.state["upload_base"])
        assertEquals("production", dump.state["reporter_environment"])
        assertEquals("LIMITED", dump.state["photo_permission"])
        assertEquals("true", dump.state["joined"])
        assertEquals("Anna's Birthday", dump.state["event_name"])
        assertEquals("Both", dump.state["direction"])
        assertEquals("true", dump.state["save_to_album"])
    }

    @Test
    fun `a membership that keeps photos out of the album says so`() = runTest {
        val dump = assembly(config = picnic(LEDGER_EVENT, album = false))(NOTE, SCREEN)

        assertEquals("false", dump.state["save_to_album"])
    }

    @Test
    fun `the grant is named by its standing - a whole library reads FULL`() = runTest {
        val named = listOf(LibraryGrant.NOT_DETERMINED, LibraryGrant.DENIED, LibraryGrant.LIMITED, LibraryGrant.GRANTED)
            .map { assembly(permission = it)(NOTE, SCREEN).state["photo_permission"] }

        assertEquals(listOf("NOT_DETERMINED", "DENIED", "LIMITED", "FULL"), named)
    }

    @Test
    fun `the mobile-data choice is a device fact reported with no event joined`() = runTest {
        // Decision record `changes/archive/2026-10-07-mobile-data-per-device`, D5.
        val setting = MobileDataStore(inMemoryPreferences())
        setting.write(MobileDataChoice.WIFI_ONLY)
        val dump = assembly(config = null, mobileData = setting)(NOTE, SCREEN)

        assertEquals("false", dump.state["joined"])
        assertEquals("off", dump.state[DiagnosticKeys.MOBILE_DATA])
    }

    @Test
    fun `the mobile-data choice reads on untouched, and unreadable for a value no build writes`() = runTest {
        val values = mutableMapOf<String, String>()
        val untouched = assembly(config = null, mobileData = MobileDataStore(inMemoryPreferences(values)))
        assertEquals("on", untouched(NOTE, SCREEN).state[DiagnosticKeys.MOBILE_DATA])

        MobileDataStore(inMemoryPreferences(values)).write(MobileDataChoice.ALLOWED)
        val chosen = assembly(config = null, mobileData = MobileDataStore(inMemoryPreferences(values)))
        assertEquals("on", chosen(NOTE, SCREEN).state[DiagnosticKeys.MOBILE_DATA])

        val locked = object : Preferences by inMemoryPreferences(values) {
            override fun get(key: String): PrefRead = PrefRead.Unavailable("locked")
        }
        val unread = assembly(config = null, mobileData = MobileDataStore(locked))
        assertEquals("unreadable", unread(NOTE, SCREEN).state[DiagnosticKeys.MOBILE_DATA])

        values.keys.forEach { values[it] = "maybe" }
        val foreign = assembly(config = null, mobileData = MobileDataStore(inMemoryPreferences(values)))
        assertEquals("unreadable", foreign(NOTE, SCREEN).state[DiagnosticKeys.MOBILE_DATA])
    }

    @Test
    fun `the device's state is written under the report's keys`() = runTest {
        val conditions = DeviceConditionsMock()
        conditions.reading = DeviceConditionsMock.TYPICAL.copy(powerSaving = Fact.Known(true))
        val dump = assembly(
            network = NetworkAccess.Online(restricted = true),
            conditions = conditions.port(),
        )(NOTE, SCREEN)

        assertEquals("online_restricted", dump.state[DiagnosticKeys.NETWORK])
        assertEquals("true", dump.state[DiagnosticKeys.POWER_SAVING])
        assertEquals("available", dump.state[DiagnosticKeys.BACKGROUND_REFRESH])
        assertEquals("80", dump.state[DiagnosticKeys.BATTERY_PERCENT])
        assertEquals("unplugged", dump.state[DiagnosticKeys.BATTERY_CHARGING])
        assertEquals("nominal", dump.state[DiagnosticKeys.THERMAL])
        assertEquals("Europe/Vienna", dump.state[DiagnosticKeys.TIME_ZONE])
        assertEquals("142", dump.state[DiagnosticKeys.MEMORY_FOOTPRINT_MB])
    }

    @Test
    fun `the network is read fresh — so an offline device says so at once`() = runTest {
        // The screen's notice waits out a grace period before it says offline; a report read at that moment would
        // still claim a network. The report reads the platform's own first answer instead.
        val dump = assembly(network = NetworkAccess.Offline)(NOTE, SCREEN)

        assertEquals("offline", dump.state[DiagnosticKeys.NETWORK])
    }

    @Test
    fun `a fact the platform does not have is left out`() = runTest {
        // The default device is an iPhone: no standby bucket, no optimisation exemption.
        footprint = null
        val dump = assembly()(NOTE, SCREEN)

        assertFalse(DiagnosticKeys.STANDBY_BUCKET in dump.state)
        assertFalse(DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT in dump.state)
        assertFalse(DiagnosticKeys.MEMORY_FOOTPRINT_MB in dump.state, "a platform that reads no footprint")
        assertFalse(DiagnosticKeys.SELECTION_PHOTOS in dump.state, "a full grant has no selection to count")
    }

    @Test
    fun `a failed fact is written failed with its reason — cut to the bound`() = runTest {
        val conditions = DeviceConditionsMock()
        conditions.reading = DeviceConditionsMock.TYPICAL.copy(
            thermal = Fact.Failed("x".repeat(500)),
            standbyBucket = Fact.Known(StandbyBucket.RARE),
        )
        val dump = assembly(conditions = conditions.port())(NOTE, SCREEN)

        assertEquals("failed (${"x".repeat(Fact.FAILURE_REASON_CHARS)})", dump.state[DiagnosticKeys.THERMAL])
        assertEquals("rare", dump.state[DiagnosticKeys.STANDBY_BUCKET])
    }

    @Test
    fun `a device read that never answers fails its facts and the report still goes`() = runTest {
        val conditions = DeviceConditionsMock().also { it.held = true }
        val dump = assembly(appLog = "app\n", conditions = conditions.port())(NOTE, SCREEN)

        val expected =
            "failed (the device conditions read timed out after ${DeviceFacts.DEFAULT_READ_TIMEOUT})"
        assertEquals(expected, dump.state[DiagnosticKeys.POWER_SAVING])
        assertEquals(expected, dump.state[DiagnosticKeys.THERMAL])
        assertEquals("online", dump.state[DiagnosticKeys.NETWORK], "the other reads are not held up by it")
        assertEquals("app\n", dump.appLog)
    }

    @Test
    fun `a network that never answers is written failed and the report still goes`() = runTest {
        val dump = assembly(network = null)(NOTE, SCREEN)

        assertEquals(
            "failed (the network read timed out after ${DeviceFacts.DEFAULT_READ_TIMEOUT})",
            dump.state[DiagnosticKeys.NETWORK],
        )
        assertEquals("nominal", dump.state[DiagnosticKeys.THERMAL], "the other reads are not held up by it")
    }

    @Test
    fun `every network access is named`() = runTest {
        val named = listOf(
            NetworkAccess.Online(restricted = true),
            NetworkAccess.Offline,
            NetworkAccess.Blocked,
        ).map { assembly(network = it)(NOTE, SCREEN).state[DiagnosticKeys.NETWORK] }

        assertEquals(listOf("online_restricted", "offline", "blocked"), named)
    }

    @Test
    fun `a partial grant reports the selection's size - and a selection not read yet is failed`() = runTest {
        assertEquals(
            "failed (the selection has not been read yet)",
            assembly(permission = LibraryGrant.LIMITED)(NOTE, SCREEN).state[DiagnosticKeys.SELECTION_PHOTOS],
        )

        val twelve = SelectionSnapshot((1..12).map { LibraryAssets.photo("asset-$it") })
        selection.published(flowOf(twelve), beforePublish = NothingWithheld).collect()
        val dump = assembly(permission = LibraryGrant.LIMITED)(NOTE, SCREEN)

        assertEquals("12", dump.state[DiagnosticKeys.SELECTION_PHOTOS])
    }

    @Test
    fun `what the screen showed is carried verbatim`() = runTest {
        val shown = mapOf(DiagnosticKeys.SHOWN_SHARED to "3/10", DiagnosticKeys.SHOWN_RECEIVED to "off")
        val dump = assembly()(NOTE, ReportContext("Joined", shown))

        assertEquals("3/10", dump.state[DiagnosticKeys.SHOWN_SHARED])
        assertEquals("off", dump.state[DiagnosticKeys.SHOWN_RECEIVED])
    }

    /**
     * The state section's share of the whole-event sum (`DiagnosticDump.kt`'s table): ≤ 4,000 bytes with the note.
     * Built at its worst — every fact failed at the longest reason, a 100-char event name, a 200-char note — so a later
     * field that would push the event over the reporting server's ceiling fails here rather than on a phone.
     */
    @Test
    fun `the state section at its worst stays inside its row of the event budget`() = runTest {
        val longest = "y".repeat(500)
        val conditions = DeviceConditionsMock().also { it.reading = DeviceConditionsReading.failed(longest) }
        identity = unreadableIdentity()
        val dump = assembly(
            config = picnic("5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e", name = "n".repeat(100), direction = Direction.Both),
            permission = LibraryGrant.LIMITED,
            conditions = conditions.port(),
            environment = environment(
                appVersion = "10.20",
                buildNumber = "99999",
                osVersion = "iOS 26.6.2 (Build 23G100)",
                deviceModel = "iPhone17,2",
            ),
        )("z".repeat(200), ReportContext("Joined", mapOf(DiagnosticKeys.SHOWN_SHARED to "99999/99999")))

        val sections = dump.state + dump.ledger + dump.snapsync()
        val bytes = dump.note.encodeToByteArray().size +
            sections.entries.sumOf { (k, v) -> k.encodeToByteArray().size + v.encodeToByteArray().size + 6 }
        assertTrue(bytes <= 4_000, "the note, state, ledger and ids came to $bytes bytes against their 4,000-byte row")
    }

    @Test
    fun `an unjoined device reports no membership fields`() = runTest {
        val dump = assembly(config = null)(NOTE, SCREEN)

        assertEquals("false", dump.state["joined"])
        assertFalse("event_name" in dump.state)
    }

    // ---- the ledger ----

    @Test
    fun `the ledger section is five labelled counts and no rows`() = runTest {
        val ledger = TestLedger()
        ledger.put(LEDGER_EVENT, "asset-1-primary.jpg", LedgerState.COMPLETED)
        ledger.put(LEDGER_EVENT, "asset-2-primary.jpg", LedgerState.REQUESTED)
        val joined = picnic(LEDGER_EVENT)

        val dump = assembly(config = joined, ledger = ledger)(NOTE, SCREEN)

        assertEquals(
            setOf(
                "photos_pending",
                "photos_completed",
                "downloads_imported",
                "downloads_assets",
                "downloads_in_flight",
            ),
            dump.ledger.keys,
            "the ledger section grew beyond the five existing counts — row lists are unbounded on the " +
                "stuck device worth reporting from",
        )
        assertEquals("1", dump.ledger["photos_pending"])
        assertEquals("1", dump.ledger["photos_completed"])

        // Rows that cannot be read are counted failed, never as none, and the report is still assembled.
        val locked = TestLedger(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked"))))
        val unread = assembly(config = joined, ledger = locked)(NOTE, SCREEN)
        assertEquals("failed (the upload ledger cannot be read: locked)", unread.ledger["photos_pending"])
        assertEquals("failed (the upload ledger cannot be read: locked)", unread.ledger["photos_completed"])
    }

    @Test
    fun `a download store that cannot be read is never reported as nothing received`() = runTest {
        val locked = receivingCounts(inMemoryDatabases(mapOf(DOWNLOADS_DB_NAME to DbOpen.Failed("locked"))))
        val dump = assembly(downloads = locked)(NOTE, SCREEN)
        listOf("downloads_imported", "downloads_assets", "downloads_in_flight").forEach { count ->
            assertEquals(
                "failed (the download store cannot be read: locked)",
                dump.ledger[count],
                "$count is failed, and the report is still assembled",
            )
        }
    }

    // ---- the note ----

    @Test
    fun `the state section names the screen the report came from`() = runTest {
        // The one fact no other section can carry: a screen-local surface touches no port, so it
        // reaches neither the ledger nor a log line.
        val dump = assembly(appLog = "app\n")(NOTE, ReportContext("Reconfigure"))

        assertEquals("Reconfigure", dump.state["screen"])
    }

    @Test
    fun `the note is carried unchanged`() = runTest {
        // Neither trimmed nor truncated here: the input component owns the bound, and two owners of
        // one number disagree eventually. Whatever the sheet sent is what the report carries.
        val written = "  spaces and a 240-char-ish sentence that the sheet already decided to allow  "
        val dump = assembly(appLog = "app\n")(written, SCREEN)

        assertEquals(written, dump.note)
    }

    @Test
    fun `the note does not eat the log budget`() = runTest {
        // The budget bounds LOG bytes. If the note were ever subtracted from it, a long note would
        // silently shorten the tails — the diagnostic quietly degrading the diagnostic.
        val long = "x".repeat(400)
        val withNote = assembly(appLog = logLines("app", 500), extLog = logLines("ext", 500))(long, SCREEN)
        val withoutNote = assembly(appLog = logLines("app", 500), extLog = logLines("ext", 500))("", SCREEN)

        assertEquals(withoutNote.appLog, withNote.appLog)
        assertEquals(withoutNote.extensionLog, withNote.extensionLog)
        assertTrue(withNote.logBytes <= budget)
    }

    private fun picnic(
        event: String,
        name: String = "Picnic",
        direction: Direction = Direction.Both,
        album: Boolean = true,
    ) = LegacyMembership(
        eventId = testEventId(event),
        name = name,
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-08T00:00:00Z")),
        direction = direction,
        saveToAlbum = album,
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )

    /** A network monitor that never answers: the platform's watch never yields its first access. */
    private object Silent : NetworkMonitor {
        override fun watch(): Flow<NetworkAccess> = flow { awaitCancellation() }
    }

    private companion object {
        val VIENNA: TimeZone = TimeZone.of("Europe/Vienna")
    }
}

/** Bytes of log [this] carries — what `LOG_BUDGET_BYTES` bounds; the note is not log. */
private val DiagnosticDump.logBytes: Int
    get() = appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size

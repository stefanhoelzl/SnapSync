package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.Availability
import app.snapsync.model.NetworkAccess
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashLevel
import app.snapsync.model.CrashOptions
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.Crumb
import app.snapsync.model.DeviceManifest
import app.snapsync.model.FileArea
import app.snapsync.model.GalleryAccess
import app.snapsync.model.PushEndpoint
import app.snapsync.model.Reply
import app.snapsync.model.SecureSlots
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadError
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.CrashHandlers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

/**
 * Every mocked system's durable state survives its text (`docs/testing.md`, "Launch-time adapters"): a device
 * driven through its ports and levers, encoded and restored into a fresh device, encodes to the same text — and the
 * restored mocks answer as the originals did.
 */
class MockStateTest {

    /** [device]'s state for every system, as text. */
    private fun texts(device: MockDevice) = MockedSystem.entries.associateWith { MockState.encode(device, it) }

    private fun copyOf(device: MockDevice): MockDevice {
        val fresh = MockDevice(ownDeviceId = device.ownDeviceId, osDrivenUpload = true)
        MockedSystem.entries.forEach { system -> MockState.encode(device, system)?.let { MockState.restore(fresh, system, it) } }
        return fresh
    }

    @Test
    fun a_driven_device_round_trips_through_its_text_system_by_system() = runTest {
        val device = drive()
        val copy = copyOf(device)
        assertEquals(texts(device), texts(copy))
    }

    @Test
    fun the_restored_backend_answers_as_the_original_did() = runTest {
        val device = drive()
        val copy = copyOf(device)
        val event = device.backend.operator.let { op -> device.backend.state.events.keys.single().also { assertTrue(op.isRegistered(it)) } }
        assertEquals("Party", copy.backend.operator.eventNameOf(event))
        assertTrue(device.backend.operator.objectsOf(DEVICE).isNotEmpty())
        assertEquals(device.backend.operator.objectsOf(DEVICE), copy.backend.operator.objectsOf(DEVICE))
        assertEquals(1, copy.backend.operator.publishesOf(event, DEVICE))
        assertEquals(PushEndpoint("apns", "tok", "sandbox"), copy.backend.operator.deviceConfigOf(DEVICE))
        // The wake for the gained photo names the union position it announced, and the copy keeps it.
        val position = device.backend.operator.unionPositionOf(event)
        assertTrue(position > 0)
        assertEquals(listOf(SentPush(event, OTHER, "other-token", seq = position)), copy.backend.operator.pushesSent())
        assertEquals(position, copy.backend.operator.unionPositionOf(event), "and so does the union's position")
        assertIs<Reply.Ok<*>>(copy.backend.port(app.snapsync.mock.DeclaredVersion("99.0")).getEvent(null, event))
    }

    @Test
    fun a_refused_phone_stays_refused_across_a_relaunch_and_is_told_why() = runTest {
        val device = MockDevice(ownDeviceId = DEVICE, osDrivenUpload = true)
        device.backend.operator.refuseAttestation = app.snapsync.model.DeviceRefusal.DEVICE_MODIFIED
        val copy = copyOf(device)
        assertEquals(app.snapsync.model.DeviceRefusal.DEVICE_MODIFIED, copy.backend.operator.refuseAttestation)

        val backend = copy.backend.port()
        val challenge = (backend.challenge() as Reply.Ok).value
        val proof = "attestation:k:$challenge".encodeToByteArray()
        val mint = backend.mintToken(app.snapsync.model.MintRequest(DEVICE, "k", app.snapsync.model.ProofFormat.APP_ATTEST, proof, challenge))
        assertEquals(Reply.Refused(401, "attestation rejected: device-modified"), mint, "even a genuine proof, as v2 names it")
        assertEquals(401, (backend.createEvent(null, CreateEventRequest("Party", "2026-06-01T00:00:00Z", null)) as Reply.Refused).status)

        copy.backend.operator.refuseAttestation = null
        assertIs<Reply.Ok<*>>(backend.createEvent(null, CreateEventRequest("Party", "2026-06-01T00:00:00Z", null)))
    }

    @Test
    fun the_restored_library_keeps_its_photos_grant_selection_and_albums() = runTest {
        val copy = copyOf(drive())
        assertEquals(listOf("A1", "S1"), copy.library.operator.current().map { it.assetId.value })
        assertTrue(copy.library.operator.current()[1].facts.isScreenshot)
        assertEquals(GalleryAccess.LIMITED, copy.library.operator.access)
        assertEquals(listOf("A1"), copy.library.operator.selection.value?.map { it.assetId.value })
        assertEquals(listOf("album-0" to "Party"), copy.library.operator.created)
        assertEquals(listOf(AssetId("A1")), copy.library.operator.assetsIn("album-0"))
        assertEquals(listOf(AssetRef("D2", AssetId("F1"))), copy.library.operator.imports.imported)
    }

    @Test
    fun the_restored_disk_keychain_clock_and_queue_answer_as_the_originals_did() = runTest {
        val copy = copyOf(drive())
        assertEquals("{}", copy.disk.operator.read(FileArea.SHARED, "eventconfig.json")?.decodeToString())
        assertTrue(copy.disk.operator.isDenied(FileArea.PRIVATE, "secret"))
        assertEquals(Instant.parse("2026-07-01T12:00:00Z"), copy.clock.operator.now)
        assertEquals(TimeZone.of("Europe/Berlin"), copy.clock.port().timeZone())
        assertEquals(mapOf(WakeId.Heartbeat to WakeTrigger.After(1.hours, network = WakeNetwork.ANY)), copy.wakes.operator.pendingWakes)
        assertEquals(listOf("A1-primary.jpg"), copy.uploadQueue.operator.liveJobKeys())
        assertEquals(1, copy.uploadQueue.operator.created.size)
        assertEquals(true, copy.extensionRegistry.operator.registered)
        assertEquals(Availability.UNAVAILABLE, copy.processInfo.operator.protectedData)
        assertEquals(NetworkAccess.Blocked, copy.connectivity.operator.access)
        assertEquals(CONDITIONS, copy.deviceConditions.operator.reading)
        assertEquals(listOf("hello"), copy.systemUi.operator.shared.value)
        assertEquals(listOf("Party"), copy.systemUi.operator.sharedTitles.value)
        assertEquals("dump", copy.crashReporter.operator.sent.value.single().message)
        assertEquals(1, copy.enclave.keys.snapshot().second.size)
        assertEquals("v", (copy.keychain.items[SLOT])?.value)
    }

    @Test
    fun systems_a_process_holds_keep_nothing() {
        val device = MockDevice()
        listOf(MockedSystem.DATABASES, MockedSystem.BACKGROUND_TIME, MockedSystem.LINKS, MockedSystem.SCREEN).forEach {
            assertNull(MockState.encode(device, it), "$it is held by a process, not kept")
        }
    }

    /** A device driven through its ports and levers into a state every system has something of. */
    private suspend fun drive(): MockDevice {
        val device = MockDevice(ownDeviceId = DEVICE, osDrivenUpload = true)
        val backend = device.backend.port()
        val event = (backend.createEvent(null, CreateEventRequest("Party", "2026-06-01T00:00:00Z", null)) as Reply.Ok).value.eventId
        backend.joinEvent(null, event, DEVICE)
        backend.joinEvent(null, event, OTHER)
        backend.putDeviceConfig(null, DEVICE, PushEndpoint("apns", "tok", "sandbox"))
        backend.putDeviceConfig(null, OTHER, PushEndpoint("apns", "other-token", "sandbox"))
        val asset = foreignAsset("A1")
        backend.publishManifest(null, event, DEVICE, DeviceManifest(DEVICE, listOf(asset), version = 3))
        device.backend.operator.deposit(DEVICE, AssetId("A1"), app.snapsync.model.ResourceRole.PRIMARY, "A1-primary.jpg")
        device.backend.operator.minAppVersion = "0.5"

        device.library.operator.add(LibraryAssets.photo("A1"))
        device.library.operator.add(LibraryAssets.screenshot("S1"))
        device.library.operator.access = GalleryAccess.LIMITED
        device.library.state.selection.value = listOf(LibraryAssets.photo("A1"))
        val gallery = device.library.port()
        val album = checkNotNull(gallery.createAlbum("Party"))
        gallery.addToAlbum(album, setOf(AssetId("A1")))
        device.library.state.imports.restoreImported(listOf(AssetRef("D2", AssetId("F1"))))

        device.disk.operator.write(FileArea.SHARED, "eventconfig.json", "{}".encodeToByteArray())
        device.disk.operator.write(FileArea.PRIVATE, "secret", byteArrayOf(0, 1, 2, -1))
        device.disk.operator.deny(FileArea.PRIVATE, "secret")
        device.preferences.port().set("k", "v")
        device.keychain.port().write(SLOT, "v")
        device.enclave.port(available = true).prove("challenge", null)
        device.crashReporter.port().apply {
            listen(CrashHandlers(onEvent = { it }, onBreadcrumb = { it }))
            start(CrashOptions("dsn"))
            sendDump(CrashEvent(message = "dump", breadcrumbs = listOf(Crumb(CrashLevel.INFO, "c")), contexts = mapOf("note" to mapOf("text" to "t"))))
        }
        device.processInfo.operator.protectedData = Availability.UNAVAILABLE
        device.connectivity.operator.access = NetworkAccess.Blocked
        device.deviceConditions.operator.reading = CONDITIONS
        device.clock.operator.now = Instant.parse("2026-07-01T12:00:00Z")
        device.clock.zone = TimeZone.of("Europe/Berlin")
        device.wakes.port().schedule(WakeId.Heartbeat, WakeTrigger.After(1.hours, network = WakeNetwork.ANY))
        device.extensionRegistry.port().setEnabled(true)
        device.uploadQueue.port().create(
            UploadSource.Resource(Unit),
            UploadTarget(
                "https://x/api/v2/files/devices/$DEVICE/A1/primary?filename=A1-primary.jpg",
                mapOf("Content-Type" to "image/jpeg"),
                TransferNetwork.UNRESTRICTED_ONLY,
            ),
            "A1-primary.jpg",
        )
        device.uploadQueue.operator.failJob("A1-primary.jpg", UploadError.Http(503))
        device.uploadSession.handbacks = 2
        device.downloads.port().start("https://in-memory.store/D2/F1", "F1-primary", TransferNetwork.UNRESTRICTED_ONLY)
        device.lifecycle.everActive = true
        device.pushService.port().register()
        device.systemUi.port().share("hello", "Party")
        device.systemUi.port().openSettings()
        return device
    }

    private fun foreignAsset(id: String) = app.snapsync.model.DeviceManifestAsset(
        AssetId(id),
        LibraryAssets.DEFAULT_DATE,
        listOf(app.snapsync.model.ManifestResource(app.snapsync.model.ResourceRole.PRIMARY, "image/jpeg", "$id-primary.jpg", "$id-primary.jpg")),
    )

    private companion object {
        const val DEVICE = "00000000-0000-4000-9000-00000000000d"
        const val OTHER = "00000000-0000-4000-9000-00000000000e"
        /** One of the app's own Keychain slots — a restore puts an item back only at a slot the app addresses. */
        val SLOT = SecureSlots.ATTEST_TOKEN
    }

    private val CONDITIONS = DeviceConditionsMock.TYPICAL.copy(
        powerSaving = app.snapsync.model.Fact.Known(true),
        thermal = app.snapsync.model.Fact.Failed("thermal: no answer"),
    )
}

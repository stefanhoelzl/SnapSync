package app.snapsync.android.gallery

import android.app.Application
import android.content.ContentValues
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.control.Verifies
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.WriteOutcome
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Which of an Android member's photos the gallery reads under a full grant — the folder decision only this adapter
 * makes (capability `photo-sharing`, "On Android under full access, only photos in the DCIM folder are shared"): `DCIM`
 * on every storage volume, nothing outside it, and a photo moved out of it no longer. The selection policy above it
 * decides the rest (`SelectionPolicyIntegrationTest`), over what this reads. And the original's location, which Android
 * hands over only to an app that asks for the original (`MediaOriginals`).
 *
 * Each test seeds its own photos at its own capture time, far from every contract's window, and deletes them after.
 */
class AndroidSharedFoldersTest {

    private val reader by lazy {
        val foreground = ForegroundActivity(context.applicationContext as Application)
        AndroidGalleryReader(context, AndroidPhotoPermission(context, foreground)::current)
    }
    private val seeded = mutableListOf<Uri>()
    private val savedByAnotherApp = mutableListOf<String>()
    private var virtualDisk = false

    @BeforeTest
    fun grant() = MediaStoreSeeder.grantFull()

    @AfterTest
    fun cleanUp() {
        seeded.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        savedByAnotherApp.forEach { runCatching { MediaStoreSeeder.deleteSavedByAnotherApp(it) } }
        if (virtualDisk) shell("sm set-virtual-disk false")
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A camera photo is shared",
    )
    fun `a photo the camera saves in DCIM is read`(): Unit = runBlocking {
        // The adapter's part: the camera's folder is read. That an ordinary photo is then admitted is the policy's.
        val id = seed(MediaStoreSeeder.CAMERA, CAMERA_DAY)
        assertEquals(setOf(id), readIn(CAMERA_DAY), "a photo in DCIM/Camera is a candidate to share")
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A full-resolution photo saved from a messenger is not shared",
    )
    fun `a full-resolution photo a messenger saves outside DCIM is not read`(): Unit = runBlocking {
        // Full resolution, so no compression rule of the policy would catch it: the folder alone keeps it private.
        val whatsApp = seed("Pictures/WhatsApp/", MESSENGER_DAY)
        val telegram = seed("Pictures/Telegram/", MESSENGER_DAY)
        assertEquals(emptySet(), readIn(MESSENGER_DAY), "a messenger's folders are not the member's gallery")
        assertEquals(GalleryRead.Read(emptyList()), reader.assetsById(setOf(whatsApp, telegram)))
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "Moving a photo out of DCIM withdraws it",
    )
    fun `a photo moved out of DCIM is no longer read`(): Unit = runBlocking {
        // The adapter's part: the moved photo reads as gone, as a deleted one does; withdrawing what is gone is the core's.
        val id = seed(MediaStoreSeeder.CAMERA, MOVED_DAY)
        assertEquals(setOf(id), readIn(MOVED_DAY), "read while it is in DCIM")
        val moved = context.contentResolver.update(
            seeded.last(),
            ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Moved/") },
            null,
            null,
        )
        assertEquals(1, moved, "MediaStore moved the photo")
        assertEquals(emptySet(), readIn(MOVED_DAY), "moved out of DCIM, it is no longer a candidate")
        assertEquals(GalleryRead.Read(emptyList()), reader.assetsById(setOf(id)), "and reads as gone by its id")
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "A photo on the SD card is shared",
    )
    fun `a photo the camera saves to an SD card’s DCIM is read like one on the phone’s own storage`(): Unit = runBlocking {
        val volume = mountVirtualSdCard()
        val id = seedOnSdCard(volume, SD_CARD_DAY)
        assertEquals(setOf(id), readIn(SD_CARD_DAY), "DCIM on the SD card ($volume) is the member's gallery too")
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only the original is uploaded, complete",
        scenario = "The capture location travels with the photo",
    )
    fun `an original is read with the location its camera recorded`(): Unit = runBlocking {
        // The adapter's part on the sharing phone: the bytes it hands over for upload keep the location. Android's
        // documentation has an app ask for the original to get it (`MediaOriginals`); on the API 36 emulator, holding
        // `ACCESS_MEDIA_LOCATION`, even a plain read carried it (measured 2026-11-07), so this holds the outcome, not
        // the request. The core passes these bytes through untouched; the receiving import keeps them too
        // (`AndroidImportContractTest`, the re-encoded Live Photo). Saved by another app, as a camera app saves it.
        val (id, path) = MediaStoreSeeder.seedSavedByAnotherApp(
            MediaStoreSeeder.CAMERA,
            LOCATION_DAY.toString(),
            LATITUDE to LONGITUDE,
        )
        savedByAnotherApp += path
        val resources = assertIs<GalleryRead.Read<List<GalleryAssetResources>>>(reader.resources(setOf(id)))
        val photo = resources.value.single().resources.single { it.kind == ResourceKind.PHOTO }
        val to = File(context.cacheDir, "location-${System.nanoTime()}.jpg")
        try {
            assertEquals(WriteOutcome.Ok, reader.export(photo.handle, to.absolutePath))
            val latLong = FloatArray(2)
            assertTrue(ExifInterface(to.absolutePath).getLatLong(latLong), "the exported original carries its location")
            assertEquals(LATITUDE.toFloat(), latLong[0], DEGREES_TOLERANCE)
            assertEquals(LONGITUDE.toFloat(), latLong[1], DEGREES_TOLERANCE)
        } finally {
            to.delete()
        }
    }

    /** The ids the gallery reads among the photos captured at [instant]. */
    private suspend fun readIn(instant: Instant): Set<PlatformAssetId> {
        val read = assertIs<GalleryRead.Read<List<GalleryAsset>>>(
            reader.assets(AssetFilter(createdFrom = instant - WINDOW, createdUntil = instant + WINDOW)),
        )
        return read.value.mapTo(mutableSetOf()) { it.id }
    }

    private fun seed(
        folder: String,
        instant: Instant,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    ): PlatformAssetId = MediaStoreSeeder.seedOne(folder, instant.toString(), volume).also {
        seeded += it.second
    }.first

    /**
     * An SD card, as the platform's own storage manager makes one: a virtual disk partitioned as public storage, which
     * MediaStore attaches as a volume of its own. Answers that volume's MediaStore name; removed after the test.
     */
    private fun mountVirtualSdCard(): String {
        val before = MediaStore.getExternalVolumeNames(context)
        virtualDisk = true
        shell("sm set-virtual-disk true")
        val disk = awaitValue("a virtual disk") {
            shell("sm list-disks").lines().map(String::trim).firstOrNull { it.startsWith("disk:") }
        }
        if (shell("sm list-volumes public").lines().none { " mounted" in it }) shell("sm partition $disk public")
        return awaitValue("the SD card's MediaStore volume") {
            (MediaStore.getExternalVolumeNames(context) - before).firstOrNull()
                ?: MediaStore.getExternalVolumeNames(context).firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        }
    }

    /**
     * A photo seeded onto the SD card [volume] once MediaStore takes it. MediaStore names a volume before it attaches
     * it, and an insert in between answers "Volume … not found" (seen on CI's emulator, run 42370879280), so the insert
     * is repeated until the volume is attached.
     */
    private fun seedOnSdCard(volume: String, instant: Instant): PlatformAssetId = awaitValue("an insert onto $volume") {
        try {
            seed(MediaStoreSeeder.CAMERA, instant, volume)
        } catch (notYet: IllegalArgumentException) {
            if ("not found" in notYet.message.orEmpty()) null else throw notYet
        }
    }

    private fun <T : Any> awaitValue(what: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + MOUNT_MILLIS
        while (true) {
            read()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "$what did not appear within ${MOUNT_MILLIS}ms" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().decodeToString() }

    private companion object {
        // Each test's own capture time, in 1999: no contract's window comes near it, and no two tests share one.
        val CAMERA_DAY: Instant = Instant.parse("1999-03-02T12:00:00Z")
        val MESSENGER_DAY: Instant = Instant.parse("1999-03-04T12:00:00Z")
        val MOVED_DAY: Instant = Instant.parse("1999-03-06T12:00:00Z")
        val SD_CARD_DAY: Instant = Instant.parse("1999-03-08T12:00:00Z")
        val LOCATION_DAY: Instant = Instant.parse("1999-03-10T12:00:00Z")

        val WINDOW = 1.hours
        const val LATITUDE = 48.137154
        const val LONGITUDE = 11.576124
        const val DEGREES_TOLERANCE = 0.0001f
        const val MOUNT_MILLIS = 60_000L
        const val POLL_MILLIS = 250L
    }
}

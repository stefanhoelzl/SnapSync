package app.snapsync.rig

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.levers.FilesLevers
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.KeychainLevers
import app.snapsync.levers.LogProcess
import app.snapsync.levers.OlderInstallLevers
import app.snapsync.mock.FileSystemMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.AssetRef
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.model.PlannedResource
import app.snapsync.model.SAVED_DIAGNOSTIC_REPORT_PATH
import app.snapsync.ports.SecureSlot
import app.snapsync.services.crash.FootprintTrail
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.secure.SecureSlots
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import app.snapsync.services.staging.StagingService

// The store services the channel implements itself rather than a mock's operator (`docs/testing.md`, "Typed services"):
// each names what it acts on in the app's own terms — a device log by its process, the staging directory, a Keychain item
// by what it is, an older build's leftovers by what that build wrote — which only code that sees the app's services can
// map to the stores' addresses.

/**
 * [FilesLevers] over the mocked disk: a device log grown, and what the device's files hold, counted as the app's own
 * services lay them out — the staging directory, and the storage the app takes with its diagnostics left out.
 */
internal class DeviceFiles(private val disk: FileSystemMock) : FilesLevers {
    override suspend fun appendLog(process: LogProcess, text: String) = when (process) {
        LogProcess.APP -> disk.append(FileArea.PRIVATE, APP_LOG_FILE_NAME, text)
        LogProcess.EXTENSION -> disk.append(FileArea.SHARED, EXTENSION_LOG_FILE_NAME, text)
    }

    override suspend fun stagedFiles(): Int = disk.stagedFiles()

    override suspend fun appStorageBytes(): Long = disk.appStorageBytes()
}

/** [KeychainLevers] over the mocked Keychain: an item named by what it is, forgotten at the address the app keeps it. */
internal class Keychain(private val world: MockWorld) : KeychainLevers {
    override suspend fun forget(item: KeychainItem) = world.device.keychain.forget(slotOf(item))

    private fun slotOf(item: KeychainItem): SecureSlot = when (item) {
        KeychainItem.EVENT_KEY -> SecureSlots.EVENT_KEY
    }
}

/**
 * [OlderInstallLevers] over the mocked databases and disk: what an older build left, written over the device's own stores
 * through the operator's hand (no armed fault reaches it) — the one service that writes app-private state, deliberately:
 * no path in the current app produces it, so the honest way to reach it is to write what the older build wrote.
 */
internal class OlderInstall(private val world: MockWorld) : OlderInstallLevers {
    override suspend fun stagedBacklog(device: String?, asset: String?): Long {
        val who = DeviceId.parse(device ?: LEGACY_DEVICE) ?: throw IllegalArgumentException("device is not a device id")
        val ref = AssetRef(who, AssetId(asset ?: "LEGACY"))
        val downloads = DownloadService(world.device.databases.inspectorPort())
        val root = StagingService(world.device.disk.port()).stagingRoot()
        val primaryKey = "${ref.sourceAssetId}-primary.heic"
        val liveKey = "${ref.sourceAssetId}-live.mov"
        val paths = listOf("$root/$primaryKey", "$root/$liveKey")
        downloads.plan(
            ref,
            LibraryAssets.DEFAULT_DATE,
            listOf(
                PlannedResource(primaryKey, "https://mock.edge/p", "primary", "image/heic", "IMG.HEIC"),
                PlannedResource(liveKey, "https://mock.edge/l", "live", "video/quicktime", "IMG.MOV"),
            ),
        )
        downloads.markStaged(ref, primaryKey, paths[0])
        downloads.markStaged(ref, liveKey, paths[1])
        downloads.markImported(ref, AssetId("LOCAL-${ref.sourceAssetId}"))
        paths.forEach { world.device.disk.write(FileArea.SHARED, it, LEGACY_BYTES) }
        return paths.size.toLong() * LEGACY_BYTES.size
    }

    private companion object {
        /** The device a backlog is seeded for when the caller names none — a device id like every other. */
        const val LEGACY_DEVICE = "1e6ac700-0000-4000-8000-000000000001"

        /** What each staged file holds: enough that freeing it shows in the storage the app takes. */
        val LEGACY_BYTES = ByteArray(64 * 1024) { 's'.code.toByte() }
    }
}

/**
 * The storage the app takes on the device, as Settings' storage page shows a person — its diagnostics left out: the
 * device logs (and their rolled siblings), the memory trail and a saved report, which grow with whatever the app does
 * and would drown out what a test measures. What `Settled.state` and [FilesLevers.appStorageBytes] carry.
 */
internal fun FileSystemMock.appStorageBytes(): Long = bytes { _, path -> path in DIAGNOSTICS }

/** The files in the download staging directory, counted over one snapshot of the shared area. */
internal fun FileSystemMock.stagedFiles(): Int = paths(
    FileArea.SHARED,
).count { it.startsWith("$DOWNLOAD_STAGING_DIR/") }

private val DIAGNOSTICS: Set<String> =
    listOf(APP_LOG_FILE_NAME, EXTENSION_LOG_FILE_NAME).flatMap { listOf(it, "$it.1") }.toSet() +
        FootprintTrail.PATH + SAVED_DIAGNOSTIC_REPORT_PATH

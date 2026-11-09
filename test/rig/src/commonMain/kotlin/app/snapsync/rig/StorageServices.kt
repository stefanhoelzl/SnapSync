package app.snapsync.rig

import app.snapsync.data.crypto.EncryptionSlots
import app.snapsync.data.preferences.PreferenceKeys
import app.snapsync.data.process.FootprintTrail
import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.data.report.SavedReport
import app.snapsync.data.trust.TrustSlots
import app.snapsync.data.upload.UPLOAD_STAGING_DIR
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.format.DeviceLogText
import app.snapsync.levers.DeviceLevers
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.FilesLevers
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.KeychainLevers
import app.snapsync.levers.LogProcess
import app.snapsync.levers.OlderInstallLevers
import app.snapsync.levers.PreferenceItem
import app.snapsync.levers.PreferencesLevers
import app.snapsync.levers.SeededInstall
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.mock.FileSystemMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.DbOpen
import app.snapsync.ports.FileArea
import app.snapsync.ports.SecureSlot

// The store services the channel implements itself rather than a mock's operator (`docs/testing.md`, "Typed services"):
// each names what it acts on in the app's own terms — a device log by its process, the staging directory, a Keychain
// item by what it is, an older build's leftovers by what that build wrote — which only code that sees the app's
// services can map to the stores' addresses.

/**
 * [FilesLevers] over the mocked disk: a device log grown, and what the device's files hold, counted as the app's own
 * services lay them out — the staging directory, and the storage the app takes with its diagnostics left out.
 */
internal class DeviceFiles(private val disk: FileSystemMock) : FilesLevers {
    override suspend fun appendLog(process: LogProcess, text: String) = when (process) {
        LogProcess.APP -> disk.append(FileArea.PRIVATE, DeviceLogText.APP_LOG, text)
        LogProcess.EXTENSION -> disk.append(FileArea.SHARED, DeviceLogText.EXTENSION_LOG, text)
    }

    override suspend fun stagedFiles(): Int = disk.stagedFiles()

    override suspend fun appStorageBytes(): Long = disk.appStorageBytes()

    override suspend fun fail(place: FilePlace, op: FileOp, times: Int?) = rootsOf(place).forEach { root ->
        disk.fail(FileArea.SHARED, root, op, times ?: 1, WorldEvent.FilesFaulted(place, op))
    }

    /** The directories, or root files, a place's files lie under — all in the shared area. */
    private fun rootsOf(place: FilePlace): List<String> = when (place) {
        FilePlace.UPLOAD_STAGING -> listOf(UPLOAD_STAGING_DIR)
        FilePlace.DOWNLOAD_STAGING -> listOf(StagedPath.ROOT)
        // Where 0.4 kept them, as the older-install layer writes them.
        FilePlace.LEGACY_RECORDS -> listOf(OlderInstalls.CONFIG_FILE, OlderInstalls.MANIFEST_FILE, OlderInstalls.PENDING_LEAVES_FILE)
            .map { it.substringBefore('/') }
    }
}

/** [KeychainLevers] over the mocked Keychain: an item named by what it is, acted on at the address the app keeps it. */
internal class Keychain(private val world: MockWorld) : KeychainLevers {
    override suspend fun forget(item: KeychainItem) = world.device.keychain.forget(slotOf(item))

    override suspend fun fail(item: KeychainItem, kind: StoreFault, times: Int?) =
        world.device.keychain.fail(slotOf(item), kind, times ?: 1, WorldEvent.KeychainFaulted(item, kind))

    private fun slotOf(item: KeychainItem): SecureSlot = when (item) {
        KeychainItem.EVENT_KEY -> EncryptionSlots.EVENT_KEY
        KeychainItem.DEVICE_ID -> TrustSlots.DEVICE_ID
        KeychainItem.ATTEST_TOKEN -> TrustSlots.ATTEST_TOKEN
        KeychainItem.ATTEST_KEY_ID -> TrustSlots.ATTEST_KEY_ID
    }
}

/** [PreferencesLevers] over the mocked preferences: an item named by what it is, refused at the key the app keeps it. */
internal class PreferencesFaults(private val world: MockWorld) : PreferencesLevers {
    override suspend fun fail(item: PreferenceItem, kind: StoreFault, times: Int?) =
        world.device.preferences.fail(keyOf(item), kind, times ?: 1, WorldEvent.PreferencesFaulted(item, kind))

    private fun keyOf(item: PreferenceItem): String = when (item) {
        PreferenceItem.MOBILE_DATA -> PreferenceKeys.MOBILE_DATA
        PreferenceItem.PUSH_REGISTRATION -> PreferenceKeys.LAST_REGISTERED
    }
}

/**
 * [DeviceLevers] over the mocked Keychain, databases and files: a phone before its first unlock since it restarted,
 * every one of them sealed at once — never one without the others.
 */
internal class LockedDevice(private val world: MockWorld) : DeviceLevers {
    override suspend fun lock() = seal(true)

    override suspend fun unlock() = seal(false)

    private fun seal(on: Boolean) {
        world.device.keychain.sealed = on
        world.device.databases.lock(on)
        world.device.disk.sealed = on
    }
}

/**
 * [OlderInstallLevers] over the mocked databases and disk: what an older build left, written over the device's own
 * stores through the operator's hand (no armed fault reaches it) — the one service that writes app-private state,
 * deliberately: no path in the current app produces it, so the honest way to reach it is to write what the older build
 * wrote.
 */
internal class OlderInstall(private val world: MockWorld) : OlderInstallLevers {
    override suspend fun stagedBacklog(device: String?, asset: String?): Long {
        val who = DeviceId.parse(device ?: LEGACY_DEVICE) ?: throw IllegalArgumentException("device is not a device id")
        val id = AssetId.parse(asset ?: "LEGACY")!!
        val primaryKey = "$id-primary.heic"
        val liveKey = "$id-live.mov"
        val paths = listOf("${StagedPath.ROOT}/$primaryKey", "${StagedPath.ROOT}/$liveKey")
        // An import confirmed before bytes were ever released: the row imported, its resources still staged.
        val opened = world.device.databases.inspectorPort().open(
            DOWNLOADS_DB_NAME,
            DownloadDatabase.Schema,
            readOnly = false,
        )
        val database = (opened as? DbOpen.Opened)?.database ?: error("downloads.db cannot be opened ($opened)")
        database.execute { driver ->
            driver.execute(null, IMPORTED_ROW, 4) {
                bindString(0, who.value)
                bindString(1, id.value)
                bindString(2, LibraryAssets.DEFAULT_DATE)
                bindString(3, "LOCAL-$id")
            }
            listOf(
                primaryKey to ("primary" to "image/heic"),
                liveKey to ("live" to "video/quicktime"),
            ).forEachIndexed { i, (key, kind) ->
                driver.execute(null, STAGED_RESOURCE, 6) {
                    bindString(0, who.value)
                    bindString(1, id.value)
                    bindString(2, key)
                    bindString(3, kind.first)
                    bindString(4, kind.second)
                    bindString(5, paths[i])
                }
            }
        }
        paths.forEach { world.device.disk.write(FileArea.SHARED, it, LEGACY_BYTES) }
        return paths.size.toLong() * LEGACY_BYTES.size
    }

    override suspend fun installedAt(ledgerBehind: Int?, downloadsBehind: Int?): SeededInstall =
        OlderInstalls(world).installedAt(ledgerBehind, downloadsBehind)

    override suspend fun refusedShareRange() = OlderInstalls(world).refusedShareRange()

    private companion object {
        /** The device a backlog is seeded for when the caller names none — a device id like every other. */
        const val LEGACY_DEVICE = "1e6ac700-0000-4000-8000-000000000001"

        const val IMPORTED_ROW = "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, createdLocalId) " +
            "VALUES (?, ?, 'IMPORTED', ?, ?)"

        const val STAGED_RESOURCE = "INSERT INTO downloadResource (sourceDeviceId, sourceAssetId, resourceKey, url, role, " +
            "contentType, originalFilename, stagedPath, enqueued, eventId) VALUES (?, ?, ?, 'https://mock.edge/r', ?, ?, 'IMG', ?, 1, '')"

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
).count { it.startsWith("${StagedPath.ROOT}/") }

private val DIAGNOSTICS: Set<String> =
    listOf(DeviceLogText.APP_LOG, DeviceLogText.EXTENSION_LOG).flatMap { listOf(it, "$it.1") }.toSet() +
        FootprintTrail.PATH + SavedReport.PATH

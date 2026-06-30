package app.snapsync.status

import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.rejoin.DeviceFilesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [CompletedAssetsSource] for **own-device** progress (capability `sync-status`): an asset is
 * complete when **every** resource it is expected to have is present in the device's storage
 * partition. Expected resources come from the shared [GalleryResourceEnumerator] (the same derivation
 * the producer uploads under, so app and extension agree byte-for-byte); present filenames come from
 * the per-device listing [DeviceFilesSource] (`GET /files/device/<deviceId>`). The device manifest
 * (`device.json`) is **never** read — it is write-only in v1.
 *
 * [refresh] re-enumerates the gallery, lists the device's stored files, and recomputes the
 * complete-asset id set. A failed listing **keeps the last good value** (never throws to the
 * projection). Observation-only: it never uploads or mutates storage.
 */
class OwnDeviceCompletedAssetsSource(
    private val enumerator: GalleryResourceEnumerator,
    private val files: DeviceFilesSource,
    private val deviceId: String,
) : CompletedAssetsSource {

    private val _completed = MutableStateFlow<Set<String>>(emptySet())
    override val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    override suspend fun refresh() {
        val present = files.list(deviceId).getOrNull()?.toSet() ?: return // keep last good on failure
        _completed.value = enumerator.enumerate()
            .groupBy { it.assetId }
            .filterValues { resources -> resources.all { it.filename in present } }
            .keys
    }
}

package app.snapsync.status

import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.gallery.GalleryStatusSource
import app.snapsync.rejoin.DeviceFilesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real own-device upload progress source (capability `sync-status`). It provides BOTH the
 * complete-asset set ([completed]) and the upload **total** ([size], via [GalleryStatusSource]) from a
 * single enumeration, so the two are always consistent.
 *
 * **The upload universe is this device's OWN photos** — the gallery minus any asset this device
 * *downloaded and imported* from other contributors (capability `photo-download`). Downloaded photos
 * live in the library (so the raw gallery count includes them) but are **suppressed from upload**, so
 * counting them would peg upload progress permanently below 100% (e.g. "9 of 11" when 2 are
 * downloaded). [suppressedLocalIds] (the download store's `createdLocalId` set, byte-identical to the
 * enumerator's `assetId` form) is excluded from both the total and the completed set.
 *
 * An asset is complete when **every** expected resource (shared [GalleryResourceEnumerator] derivation)
 * is present in the device's storage partition ([DeviceFilesSource] — `GET /devices/<deviceId>/files`).
 * The device manifest is never read. [refresh] re-enumerates, lists, and recomputes; a failed listing
 * keeps the last completed value (the total is enumeration-only and always refreshes).
 */
class OwnDeviceCompletedAssetsSource(
    private val enumerator: GalleryResourceEnumerator,
    private val files: DeviceFilesSource,
    private val deviceId: String,
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
) : CompletedAssetsSource, GalleryStatusSource {

    private val _completed = MutableStateFlow<Set<String>>(emptySet())
    override val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    private val _size = MutableStateFlow(0)

    /** The upload total `N`: the count of this device's OWN qualifying assets (downloads excluded). */
    override val size: StateFlow<Int> = _size.asStateFlow()

    override suspend fun refresh() {
        val suppressed = suppressedLocalIds()
        // Own universe = enumerated assets minus the ones this device downloaded + imported.
        val ownGroups = enumerator.enumerate()
            .groupBy { it.assetId }
            .filterKeys { it !in suppressed }
        _size.value = ownGroups.size // total refreshes from enumeration alone (no listing dependency)

        val present = files.list(deviceId).getOrNull()?.toSet() ?: return // keep last completed on failure
        _completed.value = ownGroups.filterValues { resources -> resources.all { it.filename in present } }.keys
    }
}

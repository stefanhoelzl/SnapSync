package app.snapsync.domain.receiving

import app.snapsync.domain.device.DeviceId

/**
 * **A whole union as a holding check counts it**: every complete photo it serves, by ref, and the [position] the read
 * covers — and no photo's name, which in an encrypted event only its key opens. What a member holds is a question of
 * which photos it settled, never of what they are called, so a member who cannot open the names (its key lost) still
 * learns that it holds everything (decision record `changes/seal-file-names`, D6).
 */
class HeldUnion private constructor(val refs: List<AssetRef>, val position: Long) {
    /** The refs of the photos the devices other than [me] shared — what this device is owed. */
    fun foreignTo(me: DeviceId): List<AssetRef> = refs.filter { it.sourceDeviceId != me }

    companion object {
        /**
         * The union a page serves at [position] (`null`: no position) with [refs] (`null`: an id that is no id) — or
         * `null` when either is not one, a page this build cannot count.
         */
        fun of(position: Long?, refs: List<AssetRef?>): HeldUnion? = when {
            position == null || refs.any { it == null } -> null
            else -> HeldUnion(refs.filterNotNull(), position)
        }
    }
}

package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.asset.RecordedAsset

/**
 * One rule of the [SelectionPolicy]: a predicate over an asset's [AssetFacts].
 *
 * **It can only subtract, never infer.** No platform marks "this device's camera took this", so each rule recognises a
 * category that is *certainly* not a capture, and everything else is admitted — a stray meme is visible and harmless,
 * a silently missing event photo is a failure nobody can notice.
 */
sealed interface SelectionRule {

    fun admits(asset: AssetFacts): Boolean

    /**
     * Whether this rule judges **what a photo is** — its origin — rather than when it was taken, whose it is, or
     * whether the membership shares at all. Exactly these stand down for a hand-picked selection: the member chose
     * each photo. No default, so a new rule says which kind it is.
     */
    val judgesWhatItIs: Boolean
}

/**
 * A rule a ledger row can be judged by: it needs only the asset's id and capture date ([RecordedAsset]). The rules
 * about what a photo is are not of this kind — a row carries no such facts, and none is invented for it.
 */
sealed interface RecordedRule : SelectionRule {
    fun admits(row: RecordedAsset): Boolean
}

/** The membership contributes nothing: its direction excludes upload. Admits no asset and no row. */
data object DenyAll : RecordedRule {
    override fun admits(asset: AssetFacts): Boolean = false
    override fun admits(row: RecordedAsset): Boolean = false
    override val judgesWhatItIs: Boolean get() = false
}

/** Captured inside the member's range, as it is shared now ([CaptureRange.sharedAt]). An undated asset is out. */
class InRange(val range: CaptureRange) : RecordedRule {
    override fun admits(asset: AssetFacts): Boolean = range.holds(asset.captureDate)
    override fun admits(row: RecordedAsset): Boolean = range.holds(row.captureDate)
    override val judgesWhatItIs: Boolean get() = false
}

/**
 * Echo suppression: not a photo this device received through SnapSync. Re-uploading one would send a foreign photo back
 * into the event.
 */
class NotEcho(private val received: Set<AssetId>) : RecordedRule {
    override fun admits(asset: AssetFacts): Boolean = asset.assetId !in received
    override fun admits(row: RecordedAsset): Boolean = row.assetId !in received
    override val judgesWhatItIs: Boolean get() = false
}

/**
 * Not in an album a messaging or social app made ([SelectionCalibration.denies]). Album membership is the one origin
 * fact not on the asset, so the ids are read once and supplied — which is also why a row can be judged by it.
 */
class NotInDenylistedAlbum(private val inDeniedAlbums: Set<AssetId>) : RecordedRule {
    override fun admits(asset: AssetFacts): Boolean = asset.assetId !in inDeniedAlbums
    override fun admits(row: RecordedAsset): Boolean = row.assetId !in inDeniedAlbums
    override val judgesWhatItIs: Boolean get() = true
}

/** Not a screenshot. Exact, and the most frequent exclusion. */
data object NoScreenshots : SelectionRule {
    override fun admits(asset: AssetFacts): Boolean = asset.kind != MediaKind.SCREENSHOT
    override val judgesWhatItIs: Boolean get() = true
}

/** Not a screen recording. */
data object NoScreenRecordings : SelectionRule {
    override fun admits(asset: AssetFacts): Boolean = asset.kind != MediaKind.SCREEN_RECORDING
    override val judgesWhatItIs: Boolean get() = true
}

/**
 * A photo at least [floor] — below it, compressed received media. A video, an edited photo (a crop renders small) and
 * an unknown area are admitted.
 */
class ImageFloor(private val floor: PixelArea.Known) : SelectionRule {
    override fun admits(asset: AssetFacts): Boolean =
        asset.kind.isMoving || asset.isEdited || asset.pixelArea.reaches(floor)
    override val judgesWhatItIs: Boolean get() = true
}

/** A video at least [floor] pixels — a pixel count, so a narrow video with more pixels than 1280×720 is admitted. */
class VideoFloor(private val floor: PixelArea.Known) : SelectionRule {
    override fun admits(asset: AssetFacts): Boolean =
        !asset.kind.isMoving || asset.isEdited || asset.pixelArea.reaches(floor)
    override val judgesWhatItIs: Boolean get() = true
}

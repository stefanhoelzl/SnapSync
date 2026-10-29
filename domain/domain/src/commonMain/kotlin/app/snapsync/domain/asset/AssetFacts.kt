package app.snapsync.domain.asset

import kotlin.jvm.JvmInline

/**
 * What the platform says an asset is — the facts the selection policy decides on, read cheaply for every asset of a
 * walk or a selection. No fact has a default: every reader states every fact, so no consumer is handed a guess.
 */
class AssetFacts(
    val assetId: AssetId,
    /** When it was captured — or [CaptureDate.Undated]: the platform holds no capture date, which no range holds. */
    val captureDate: CaptureDate,
    val kind: MediaKind,
    /**
     * Edited on the device (`hasAdjustments`). A photo cropped in Photos renders at its *cropped* size, so the
     * resolution floors are skipped for it — otherwise a genuine capture is dropped for being small.
     */
    val isEdited: Boolean,
    val pixelArea: PixelArea,
)

/**
 * What kind of media an asset is: a photo or a video, or the screen's own capture of either — which resolution floor
 * applies, and whether the platform knows it is not a camera's.
 */
enum class MediaKind {
    PHOTO,
    SCREENSHOT,
    VIDEO,
    SCREEN_RECORDING,
    ;

    /** Whether this kind is moving — the video floor's, not the image floor's. */
    val isMoving: Boolean get() = this == VIDEO || this == SCREEN_RECORDING

    companion object {
        /** The kind the platform's marks describe: a screen recording is a video, a screenshot a still. */
        fun of(isVideo: Boolean, isScreenshot: Boolean, isScreenRecording: Boolean): MediaKind = when {
            isScreenRecording -> SCREEN_RECORDING
            isVideo -> VIDEO
            isScreenshot -> SCREENSHOT
            else -> PHOTO
        }
    }
}

/**
 * An asset's pixel count as the platform reports it. A missing or non-positive count is [Unknown], never tiny: the
 * policy admits on doubt.
 */
sealed interface PixelArea {

    /** Whether this area is at least [floor]. An unknown area reaches every floor — admit on doubt. */
    fun reaches(floor: Known): Boolean

    /** At least [pixels] pixels, read from the platform's width × height. */
    @JvmInline
    value class Known internal constructor(val pixels: Long) : PixelArea {
        override fun reaches(floor: Known): Boolean = pixels >= floor.pixels
    }

    data object Unknown : PixelArea {
        override fun reaches(floor: Known): Boolean = true
    }

    companion object {
        /** The area the platform reports as [pixels] — `null` or non-positive means it does not know. */
        fun of(pixels: Long?): PixelArea = when {
            pixels == null || pixels <= 0L -> Unknown
            else -> Known(pixels)
        }
    }
}

/**
 * What a ledger row knows of its asset: its id and capture date, and nothing about what it is. Judged only by the rules
 * that need no more ([app.snapsync.domain.sharing.SelectionPolicy.admitRecorded]); the rules about what a photo is
 * cannot speak to it, and nothing stands in for the facts it lacks.
 */
class RecordedAsset(val assetId: AssetId, val captureDate: CaptureDate)

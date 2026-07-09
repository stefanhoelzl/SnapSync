package app.snapsync.ios

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIRectFill

/**
 * Epoch seconds for `2001-01-01T00:00:00Z`. Seeded assets are dated from here forward, one minute apart,
 * so they land decades before any plausible event cutoff — the set the bounded walk must exclude — and so
 * they cluster under a single year in Photos, making manual cleanup a two-tap job.
 */
private const val SEED_EPOCH_SECONDS = 978_307_200.0

/** Assets per `performChangesAndWait` transaction. One transaction for thousands of requests stalls. */
private const val SEED_CHUNK = 250

/**
 * **Dev/test only.** Seeds the photo library with `SNAPSYNC_SEED_PHOTOS` synthetic assets so a large
 * library can be exercised on device — the gallery walk's cost is per **asset** (one synchronous PhotoKit
 * XPC round-trip each), which is exactly what the capture-date bound exists to contain, and what a
 * one-photo dev device cannot demonstrate.
 *
 * Read from the process environment, which is **only injectable via a developer launch**
 * (`pymobiledevice3 developer dvt launch --env …`) — SpringBoard and TestFlight launches carry a clean
 * environment, so this is inert in production with no compile-time guard, exactly as `SNAPSYNC_DEEPLINK`
 * is (capability `ios-app-shell`).
 *
 * Assets are dated from [SEED_EPOCH_SECONDS] forward. Deleting them again needs a tap — `deleteAssets`
 * always raises a system confirmation — so they are deliberately parked in one year of the Photos
 * timeline rather than scattered across it.
 *
 * Blocking (`performChangesAndWait`), so this must not run on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
fun seedPhotoLibraryFromLaunchEnv(log: Logger) {
    val raw = NSProcessInfo.processInfo.environment["SNAPSYNC_SEED_PHOTOS"] as? String ?: return
    val count = raw.toIntOrNull()
    if (count == null || count <= 0) {
        log.w { "SNAPSYNC_SEED_PHOTOS=$raw is not a positive integer — not seeding" }
        return
    }

    log.i { "seeding $count synthetic asset(s) into the photo library (dev/test)" }
    var created = 0
    var chunk = 0
    while (created < count) {
        val size = minOf(SEED_CHUNK, count - created)
        val base = created
        val ok = PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                repeat(size) { i ->
                    val index = base + i
                    val image = solidColorImage(index)
                    if (image != null) {
                        PHAssetCreationRequest.creationRequestForAssetFromImage(image)?.apply {
                            // One minute apart, so every asset has a distinct, deterministic capture date.
                            setCreationDate(
                                NSDate.dateWithTimeIntervalSince1970(SEED_EPOCH_SECONDS + index * 60.0),
                            )
                        }
                    }
                }
            },
            error = null,
        )
        if (!ok) {
            log.e { "seeding failed at chunk $chunk (after $created asset(s))" }
            return
        }
        created += size
        chunk++
        log.i { "seeded $created/$count asset(s)" }
    }
    log.i { "seeding complete: $created asset(s)" }
}

/**
 * A distinct 64×64 solid-colour image per [index] — distinct bytes, so PhotoKit stores a separate asset
 * rather than deduplicating, and small enough that thousands cost a few megabytes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun solidColorImage(index: Int): UIImage? {
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(64.0, 64.0), true, 1.0)
    UIColor(
        red = (index % 251) / 255.0,
        green = ((index / 251) % 251) / 255.0,
        blue = ((index / 63001) % 251) / 255.0,
        alpha = 1.0,
    ).setFill()
    UIRectFill(CGRectMake(0.0, 0.0, 64.0, 64.0))
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

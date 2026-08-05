package app.snapsync.ios

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
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

/**
 * Default seed size: 64×64. Tiny on purpose — thousands of assets cost a few megabytes, and the *point* of
 * seeding is to make the **walk** expensive (one PhotoKit round-trip per asset), which is independent of
 * image size. These assets are dated 2001 and so are excluded by the cutoff anyway; they never upload.
 *
 * They are also, incidentally, three orders of magnitude below the selection policy's 3 MP image floor
 * (capability `photo-selection-policy`) — so a default seed is doubly out of scope. That is harmless for
 * the walk-cost purpose but useless for exercising an **upload**, which is what [SEED_POLICY_ENV] is for.
 */
private const val SEED_IMAGE_SIDE = 64.0

/**
 * Above-floor seed size: **2048×1536 = 3.15 MP**, just over the selection policy's 3 MP image floor — so an
 * asset this size is one the policy *admits*.
 */
private const val SEED_ABOVE_FLOOR_WIDTH = 2048.0
private const val SEED_ABOVE_FLOOR_HEIGHT = 1536.0

/**
 * `SNAPSYNC_SEED_POLICY=<n>` — the **selection-policy probe** seed (capability `photo-selection-policy`):
 * `n` assets dated from **an hour ahead**, alternating **above** and **below** the 3 MP image floor.
 *
 * It exists because neither of the other two seeds can exercise the policy on a real device:
 *
 * - a dev device may hold **no real photos at all** (the SE2 does not), so there is nothing the policy
 *   should *admit*, and a run cannot distinguish "the policy correctly excluded everything" from "the fetch
 *   predicate silently returned nothing" — which, given that the wrong predicate form returns **zero rows
 *   without raising**, is exactly the confusion that matters most;
 * - and [SEED_EPOCH_SECONDS] seeds are dated 2001, so the **cutoff** excludes them before the origin rules
 *   are ever consulted.
 *
 * Dating them ahead of *now* puts them past any cutoff an event created today can carry (the cutoff is
 * clamped to `max(chosen, startsAt)`, capability `join-event`), so the **only** thing that can separate them
 * is the resolution floor. One launch then answers every question at once: the walk returns assets (the
 * predicate is not silently empty), exactly the below-floor half is origin-excluded, `N` counts only the
 * rest, and only the rest uploads.
 */
private const val SEED_POLICY_ENV = "SNAPSYNC_SEED_POLICY"
private const val SEED_POLICY_LEAD_SECONDS = 3600.0

/**
 * Assets per `performChangesAndWait` transaction. One transaction for thousands of requests stalls.
 *
 * The above-floor chunk is far smaller because `PHAssetCreationRequest` **retains each `UIImage` until the
 * transaction commits**: a 2048×1536 image is ~12.6 MB uncompressed, so 250 of them would hold ~3 GB live
 * and be killed on an SE2. The default (tiny) seed keeps the original chunk, so the documented
 * 4000-asset / ~85 s walk-cost seed is unchanged.
 */
private const val SEED_CHUNK = 250
private const val SEED_CHUNK_ABOVE_FLOOR = 10

/**
 * **Dev/test only.** Two independent seeds, either or both:
 *
 * - `SNAPSYNC_SEED_PHOTOS=<n>` — `n` tiny assets dated 2001, so a large library can be exercised on device.
 *   The gallery walk's cost is per **asset** (one synchronous PhotoKit XPC round-trip each), which is
 *   exactly what the capture-date bound exists to contain, and what a one-photo dev device cannot
 *   demonstrate. These never upload (out of scope by date).
 * - `SNAPSYNC_SEED_POLICY=<n>` — the selection-policy probe; see [SEED_POLICY_ENV].
 *
 * Read from the process environment, which is **only injectable via a developer launch**
 * (`pymobiledevice3 developer dvt launch --env …`) — SpringBoard and TestFlight launches carry a clean
 * environment, so this is inert in production with no compile-time guard, exactly as `SNAPSYNC_EVENT_LINK`
 * is (capability `ios-app-shell`).
 *
 * Assets are dated from [SEED_EPOCH_SECONDS] forward. Deleting them again needs a tap — `deleteAssets`
 * always raises a system confirmation — so they are deliberately parked in one year of the Photos
 * timeline rather than scattered across it.
 *
 * Blocking (`performChangesAndWait`), so this must not run on the main thread.
 */
// PINNED shell decisions (spec `module-architecture`, "Shells are wiring only" — pinned forms;
// inventory gated by KotlinShellGuardTest), one pin per function below. Forcing proof: dev
// equipment that can only live in the app process — `PHAssetCreationRequest` writes the REAL photo
// library of the attached device from a launch-env trigger (injectable only via a developer
// launch, so inert in production), which no tested module can reach; the branches are operator-
// input validation and the chunking the platform forces (`PHAssetCreationRequest` retains each
// `UIImage` until the transaction commits — measured ~12.6 MB per above-floor image, so one
// transaction for thousands of requests stalls or is killed on an SE2). Expiry: dies with the
// seeder if on-device seeding is ever replaced by a simulator-only rig.
@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalForeignApi::class)
fun seedPhotoLibraryFromLaunchEnv(log: Logger) {
    val env = NSProcessInfo.processInfo.environment
    (env["SNAPSYNC_SEED_PHOTOS"] as? String)?.let { raw ->
        val count = raw.toIntOrNull()
        if (count == null || count <= 0) {
            log.w { "SNAPSYNC_SEED_PHOTOS=$raw is not a positive integer — not seeding" }
        } else {
            seedPhotos(log, count, policyProbe = false)
        }
    }
    (env[SEED_POLICY_ENV] as? String)?.let { raw ->
        val count = raw.toIntOrNull()
        if (count == null || count <= 0) {
            log.w { "$SEED_POLICY_ENV=$raw is not a positive integer — not seeding" }
        } else {
            seedPhotos(log, count, policyProbe = true)
        }
    }
}

/**
 * Seed [count] synthetic assets.
 *
 * Default: tiny (64×64), dated 2001 — the large-library / walk-cost seed. These are out of scope for any
 * plausible cutoff and never upload.
 *
 * [policyProbe]: dated **an hour ahead** (so past any cutoff an event created today can carry) and
 * **alternating** above/below the 3 MP image floor — the selection-policy probe (see [SEED_POLICY_ENV]).
 * Exactly the odd-indexed half should survive the policy.
 *
 * Blocking (`performChangesAndWait`) — never call on the main thread.
 */
@Suppress("CyclomaticComplexMethod") // pinned — see the file's pin block above
@OptIn(ExperimentalForeignApi::class)
fun seedPhotos(log: Logger, count: Int, policyProbe: Boolean = false) {
    val chunkSize = if (policyProbe) SEED_CHUNK_ABOVE_FLOOR else SEED_CHUNK
    val base0 = if (policyProbe) NSDate().timeIntervalSince1970 + SEED_POLICY_LEAD_SECONDS else SEED_EPOCH_SECONDS
    if (policyProbe) {
        val above = (count + 1) / 2 // even indices are above the floor
        log.i {
            "seeding $count POLICY-PROBE asset(s): dated ~1h ahead (in scope for any event started today), " +
                "alternating above/below the 3 MP floor — expect $above admitted, ${count - above} origin-excluded"
        }
    } else {
        log.i { "seeding $count synthetic asset(s) into the photo library (dev/test)" }
    }
    var created = 0
    var chunk = 0
    while (created < count) {
        val size = minOf(chunkSize, count - created)
        val base = created
        val ok = PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                repeat(size) { i ->
                    val index = base + i
                    // In probe mode the EVEN indices are above the floor, the odd ones below it.
                    val aboveFloor = policyProbe && index % 2 == 0
                    val image = solidColorImage(index, aboveFloor)
                    if (image != null) {
                        PHAssetCreationRequest.creationRequestForAssetFromImage(image)?.apply {
                            // One minute apart, so every asset has a distinct, deterministic capture date.
                            setCreationDate(NSDate.dateWithTimeIntervalSince1970(base0 + index * 60.0))
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
 * A distinct solid-colour image per [index] — distinct bytes, so PhotoKit stores a separate asset rather
 * than deduplicating. [aboveFloor] sizes it over the policy's 3 MP image floor; a flat colour encodes to a
 * small JPEG regardless of dimensions, so the on-disk cost stays modest either way.
 */
@Suppress("CyclomaticComplexMethod") // pinned — see the file's pin block above
@OptIn(ExperimentalForeignApi::class)
/**
 * Absence: null means the image could not be rendered, and the seeder skips that asset. Dev/test
 * only (`SNAPSYNC_SEED_PHOTOS`), never reached in production, and the consequence of any cause is
 * the same: one fewer synthetic asset in a library being filled for a manual experiment.
 */
private fun solidColorImage(index: Int, aboveFloor: Boolean): UIImage? {
    val w = if (aboveFloor) SEED_ABOVE_FLOOR_WIDTH else SEED_IMAGE_SIDE
    val h = if (aboveFloor) SEED_ABOVE_FLOOR_HEIGHT else SEED_IMAGE_SIDE
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, 1.0)
    UIColor(
        red = (index % 251) / 255.0,
        green = ((index / 251) % 251) / 255.0,
        blue = ((index / 63001) % 251) / 255.0,
        alpha = 1.0,
    ).setFill()
    UIRectFill(CGRectMake(0.0, 0.0, w, h))
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

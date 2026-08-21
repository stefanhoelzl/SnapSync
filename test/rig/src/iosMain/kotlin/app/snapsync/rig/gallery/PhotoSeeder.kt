package app.snapsync.rig.gallery

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
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
 * Epoch seconds for `2001-01-01T00:00:00Z`. Bulk-seeded assets are dated from here forward, one minute
 * apart, so they land decades before any plausible event cutoff — the set the bounded walk must exclude —
 * and so they cluster under a single year in Photos, making manual cleanup a two-tap job.
 */
private const val SEED_EPOCH_SECONDS = 978_307_200.0

/**
 * Bulk seed size: 64×64. Tiny on purpose — thousands of assets cost a few megabytes, and the *point* of
 * seeding is to make the **walk** expensive (one PhotoKit round-trip per asset), which is independent of
 * image size. These assets are dated 2001 and so are excluded by the cutoff anyway; they never upload.
 *
 * They are also, incidentally, three orders of magnitude below the selection policy's 3 MP image floor
 * (capability `photo-selection-policy`) — so a bulk seed is doubly out of scope. That is harmless for the
 * walk-cost purpose but useless for exercising an **upload**, which is what [SeedKind.POLICY] is for.
 */
private const val SEED_IMAGE_SIDE = 64.0

/**
 * Above-floor seed size: **2048×1536 = 3.15 MP**, just over the selection policy's 3 MP image floor — so an
 * asset this size is one the policy *admits*.
 */
private const val SEED_ABOVE_FLOOR_WIDTH = 2048.0
private const val SEED_ABOVE_FLOOR_HEIGHT = 1536.0

private const val SEED_POLICY_LEAD_SECONDS = 3600.0

/**
 * Assets per `performChangesAndWait` transaction. One transaction for thousands of requests stalls.
 *
 * The above-floor chunk is far smaller because `PHAssetCreationRequest` **retains each `UIImage` until the
 * transaction commits**: a 2048×1536 image is ~12.6 MB uncompressed, so 250 of them would hold ~3 GB live
 * and be killed on an SE2. The bulk seed keeps the original chunk, so the documented 4000-asset / ~85 s
 * walk-cost seed is unchanged.
 */
private const val SEED_CHUNK = 250
private const val SEED_CHUNK_ABOVE_FLOOR = 10

/**
 * What a seed is *for*. Two shapes, because they answer different questions and neither substitutes.
 *
 * They were two separate launch variables (`SNAPSYNC_SEED_PHOTOS` / `SNAPSYNC_SEED_POLICY`) only because an
 * environment variable takes no parameters. They are one function with a boolean and are now one command
 * with a parameter.
 */
enum class SeedKind {

    /**
     * `n` tiny assets dated 2001 — the large-library / walk-cost seed. The gallery walk's cost is per
     * **asset** (one synchronous PhotoKit XPC round-trip each), which is exactly what the capture-date
     * bound exists to contain, and what a one-photo dev device cannot demonstrate. These never upload.
     */
    BULK,

    /**
     * `n` assets dated **an hour ahead**, alternating **above** and **below** the 3 MP image floor — the
     * selection-policy probe (capability `photo-selection-policy`).
     *
     * It exists because neither an empty library nor a bulk seed can exercise the policy on a real device:
     *
     * - a dev device may hold **no real photos at all** (the SE2 does not), so there is nothing the policy
     *   should *admit*, and a run cannot distinguish "the policy correctly excluded everything" from "the
     *   fetch predicate silently returned nothing" — which, given that the wrong predicate form returns
     *   **zero rows without raising**, is exactly the confusion that matters most;
     * - and [SEED_EPOCH_SECONDS] assets are dated 2001, so the **cutoff** excludes them before the origin
     *   rules are ever consulted.
     *
     * Dating them ahead of *now* puts them past any cutoff an event created today can carry (the cutoff is
     * clamped to `max(chosen, startsAt)`, capability `join-event`), so the **only** thing that can separate
     * them is the resolution floor. One seed then answers every question at once: the walk returns assets
     * (the predicate is not silently empty), exactly the below-floor half is origin-excluded, `N` counts
     * only the rest, and only the rest uploads.
     */
    POLICY,
}

/** What a seed did, for the command's response. */
class SeedOutcome(val requested: Int, val created: Int, val kind: SeedKind, val failedAtChunk: Int?)

/**
 * Seed [count] synthetic assets into this device's photo library.
 *
 * Blocking (`performChangesAndWait`) — never call on the main thread. The control channel runs device
 * commands off the main lane precisely for this reason.
 *
 * This used to live in `:app:ios` and carry three pinned `detektAppShell` suppressions, justified as "dev
 * equipment, inert in production". It now lives in a module that a production build does not contain, so
 * the justification is no longer needed and neither are the suppressions: what changed is not that the
 * branches went away but that they stopped being in the shipped shell.
 */
@OptIn(ExperimentalForeignApi::class)
fun seedPhotos(log: Logger, count: Int, kind: SeedKind): SeedOutcome {
    val policyProbe = kind == SeedKind.POLICY
    val chunkSize = if (policyProbe) SEED_CHUNK_ABOVE_FLOOR else SEED_CHUNK
    val base0 = if (policyProbe) NSDate().timeIntervalSince1970 + SEED_POLICY_LEAD_SECONDS else SEED_EPOCH_SECONDS
    if (policyProbe) {
        val above = (count + 1) / 2 // even indices are above the floor
        log.i {
            "seeding $count POLICY asset(s): dated ~1h ahead (in scope for any event started today), " +
                "alternating above/below the 3 MP floor — expect $above admitted, ${count - above} origin-excluded"
        }
    } else {
        log.i { "seeding $count BULK asset(s) into the photo library" }
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
            return SeedOutcome(requested = count, created = created, kind = kind, failedAtChunk = chunk)
        }
        created += size
        chunk++
        log.i { "seeded $created/$count asset(s)" }
    }
    log.i { "seeding complete: $created asset(s)" }
    return SeedOutcome(requested = count, created = created, kind = kind, failedAtChunk = null)
}

/**
 * A distinct solid-colour image per [index] — distinct bytes, so PhotoKit stores a separate asset rather
 * than deduplicating. [aboveFloor] sizes it over the policy's 3 MP image floor; a flat colour encodes to a
 * small JPEG regardless of dimensions, so the on-disk cost stays modest either way.
 *
 * Absence: null means the image could not be rendered, and the seeder skips that asset. The consequence of
 * every cause is the same — one fewer synthetic asset in a library being filled for an experiment — and the
 * count the caller gets back is the number actually created, so a skip is visible rather than assumed.
 */
@OptIn(ExperimentalForeignApi::class)
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

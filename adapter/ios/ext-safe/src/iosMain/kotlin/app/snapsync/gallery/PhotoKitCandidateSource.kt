package app.snapsync.gallery

import app.snapsync.model.Candidate
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.CandidateSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSPredicate
import platform.Foundation.dateByAddingTimeInterval
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.UniformTypeIdentifiers.UTType

/**
 * The PhotoKit-backed [CandidateSource] (capability `gallery-status`): the one seam through which the photo
 * library is read for admission.
 *
 * It receives the membership's [SelectionPolicy] and translates the rules it can express into a
 * `PHFetchOptions` predicate ([predicateFor]); the rest fall through to the caller's authoritative
 * in-memory admission. Nothing hands it a pre-flattened capture-date bound — the three stacked seams that
 * used to relay one (`RawAssetSource` → `PhotoLibrary` → `ResourceEnumerator`) are gone.
 *
 * **Facts are cheap; resources are not.** Each candidate carries [asset facts][app.snapsync.model.AssetFacts]
 * read from plain in-memory `PHAsset` properties, and defers `PHAssetResource.assetResourcesForAsset` — a
 * *synchronous XPC* round-trip into `photolibraryd`'s `Photos.sqlite`, ~110 ms per asset on an SE2 — until
 * a consumer asks for that asset's resources. Because every selection rule decides on facts alone
 * (capability `photo-selection-policy`), a count pays nothing and an upload pays only for assets already
 * admitted. The seam this replaced read every fetched asset's resources up front and then discarded the
 * excluded ones.
 *
 * **The candidate closes over the `PHAsset`, never over its identifier.** Re-fetching by
 * `localIdentifier` at read time would be an autonomous library fetch, which under a partial grant queues
 * iOS's limited-access alert into an app-killing storm that survives process death (capability
 * `limited-photo-access`). Holding the object keeps the deferred read off the fetch path entirely.
 *
 * **Every read hops to [Dispatchers.Default]** — Kotlin/Native has no `Dispatchers.IO`. Both app-process
 * callers reach this from `SnapSyncRoot`'s `Dispatchers.Main` scope, where blocking trips the 10 s
 * scene-update watchdog and the OS kills the app (`0x8BADF00D`). The extension process calls it too, where
 * the hop is harmless (`process()` is already off-main).
 *
 * Wiring-only and untestable (PhotoKit, device/simulator only); [PhotoKitSmokeTest] confirms the glue runs
 * on the simulator, the rule translation is pinned by [PhotoKitCandidateSourceTest], and the pure mapping
 * it feeds is unit-tested in `commonTest`.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoKitCandidateSource(private val log: Logger = Logger.withTag("gallery")) : CandidateSource {

    /**
     * Resource reads issued since the last walk — the number this whole seam exists to lower
     * (capability `diagnostic-logging`).
     *
     * Without it the saving is invisible on a device: a walk that reads every fetched asset's resources
     * and one that reads only the admitted ones differ in *nothing observable* except elapsed time, which
     * is noisy. Reported against the candidate count, so the two numbers that matter — how many the fetch
     * returned, and how many were actually paid for — sit on one line.
     */
    private var resourceReads = 0

    override suspend fun candidates(policy: SelectionPolicy): List<Candidate> =
        withContext(Dispatchers.Default) {
            if (resourceReads > 0) log.i { "gallery: $resourceReads resource read(s) since the last walk" }
            resourceReads = 0
            val fetched = PHAsset.fetchAssetsWithOptions(fetchOptions(policy))
            candidatesFrom(fetched).also { log.i { "gallery: fetched ${it.size} candidate(s)" } }
        }

    /**
     * The candidates for an **already-fetched** result — no fetch of its own.
     *
     * Two callers need this, and both for the same reason: they already hold a `PHFetchResult` and must
     * not issue another fetch to reach its assets. The incremental change-feed walk fetches by identifier
     * (which takes no predicate), and the `LIMITED` selection observer holds the baseline/change result
     * whose re-fetch would be an autonomous library read — the measured alert storm (capability
     * `limited-photo-access`).
     */
    fun candidatesFrom(assets: PHFetchResult): List<Candidate> {
        val out = mutableListOf<Candidate>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            // Per-asset capture timestamp (ISO-8601), reused for every resource of the asset.
            val creationDate = asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) } ?: ""
            out += PhotoKitCandidate(asset, creationDate) { resourceReads++ }
        }
        return out
    }

    private fun fetchOptions(policy: SelectionPolicy): PHFetchOptions? =
        predicateFor(policy)?.let { predicate -> PHFetchOptions().apply { this.predicate = predicate } }
}

/**
 * One asset, facts now and resources on demand.
 *
 * The resource read is deferred but the **`PHAsset` is held**, so obtaining it later issues no fetch —
 * see [PhotoKitCandidateSource] for why that distinction is load-bearing rather than incidental.
 */
@OptIn(ExperimentalForeignApi::class)
private class PhotoKitCandidate(
    private val asset: PHAsset,
    private val creationDate: String,
    private val onResourceRead: () -> Unit,
) : Candidate {

    override val facts = asset.toAssetFacts(creationDate)

    override suspend fun resources(): List<Resource> = withContext(Dispatchers.Default) {
        onResourceRead()
        val rawResources = PHAssetResource.assetResourcesForAsset(asset).map { any ->
            val resource = any as PHAssetResource
            RawResource(
                type = resource.type, // raw PHAssetResourceType value — un-mapped
                contentTypeUti = resource.uniformTypeIdentifier,
                // Apple's UTI→MIME table stays iOS-only; commonMain must not reimplement it.
                mimeContentType = UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType
                    ?: "application/octet-stream",
                originalFilename = resource.originalFilename,
                handle = resource, // opaque PHAssetResource, crosses uninterpreted
            )
        }
        // The shared pure mapping owns the role filter, key derivation and id normalization — this adapter
        // holds none of its own, so that fan-out stays unit-tested off-device.
        resourcesFrom(listOf(RawAsset(asset.localIdentifier, creationDate, rawResources, facts)))
    }
}

/**
 * Translate a [SelectionPolicy] into a `PHFetchOptions` predicate, or `null` for no narrowing.
 *
 * The `when` is **exhaustive over the sealed rule set on purpose**: adding a rule fails to compile here
 * until someone states whether PhotoKit can express it. Before this, the predicate hardcoded a mask and a
 * cutoff, so a new rule simply never narrowed and nobody found out.
 *
 * Narrowing is an **optimization only** (capability `photo-selection-policy`): the caller's in-memory
 * admission runs over whatever comes back, so this may return a superset of the admitted set but never a
 * subset. Where the predicate could disagree with the authoritative decision at a boundary it is
 * **widened**, never narrowed.
 *
 * **Three device-verified constraints on PhotoKit's predicate parser** (SE2, iOS 26.5.2; measured facts,
 * not preferences — re-verify on a device before adding any key):
 *
 * 1. A subtype exclusion MUST be written `NOT ((mediaSubtypes & N) != 0)`. The natural
 *    `(mediaSubtypes & N) == 0` form returns **zero rows** — silently, without raising. Shipping it would
 *    starve the walk of every asset. (The *singular* `mediaSubtype` key likewise returns zero rows without
 *    raising, so a one-character typo empties the library.)
 * 2. Predicate **arithmetic** raises an uncatchable `NSException` and aborts the process, so
 *    `pixelWidth * pixelHeight` is impossible — the area floors cannot ride along.
 * 3. `hasAdjustments` is not a supported key and likewise aborts the process.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun predicateFor(policy: SelectionPolicy): NSPredicate? {
    val rules = (policy as? SelectionPolicy.Admitting)?.rules ?: return null
    val clauses = mutableListOf<String>()
    val args = mutableListOf<Any>()

    for (rule in rules) when (rule) {
        // The one REQUIRED narrowing: without a lower bound the walk is unbounded and the process is
        // watchdog-killed before the authoritative admission ever runs. Widened by a day — see [widened].
        is SelectionRule.CaptureAfter -> parseBound(rule.cutoff.at.iso)?.let {
            clauses += "creationDate >= %@"
            args += it.dateByAddingTimeInterval(-PREDICATE_WIDEN_SECONDS)
        }
        is SelectionRule.CaptureBefore -> parseBound(rule.ceiling.at.iso)?.let {
            clauses += "creationDate <= %@"
            args += it.dateByAddingTimeInterval(PREDICATE_WIDEN_SECONDS)
        }
        SelectionRule.ExcludeScreenshots ->
            clauses += "NOT ((mediaSubtypes & $SUBTYPE_SCREENSHOT) != 0)" // NB the NOT-form; see (1)
        SelectionRule.ExcludeScreenRecordings ->
            clauses += "NOT ((mediaSubtypes & $SUBTYPE_SCREEN_RECORDING) != 0)"
        // Not expressible: the area comparison needs arithmetic, which aborts the process — see (2). A
        // bounding-box approximation could ride along but is deliberately omitted: it could only ever
        // narrow, and the floors exist to be conservative.
        is SelectionRule.MinImageArea, is SelectionRule.MinVideoArea -> Unit
        // Not expressible: these are id sets resolved from the download store and the album map, not
        // properties of the asset. Both are small and applied in memory.
        is SelectionRule.NotEcho, is SelectionRule.NotInDenylistedAlbum -> Unit
    }

    if (clauses.isEmpty()) return null
    return NSPredicate.predicateWithFormat(clauses.joinToString(" AND "), argumentArray = args)
}

/**
 * Parse a canonical cutoff into an `NSDate`, tolerating **fractional seconds**.
 *
 * A bare `NSISO8601DateFormatter` uses `.withInternetDateTime`, which does not accept a `.sss` fraction and
 * returns `nil` for `2026-07-09T19:24:17.182Z`. Bounds are supposed to be second precision (capability
 * `photo-selection-policy`) and the join gate normalizes them — but one persisted by an older build carries
 * the backend's raw `toISOString()` milliseconds. Losing the predicate there would silently restore the
 * whole-library fetch that trips the watchdog, so parse both shapes rather than trust the invariant.
 */
private fun parseBound(iso: String): NSDate? =
    NSISO8601DateFormatter().apply { formatOptions = NSISO8601DateFormatWithInternetDateTime }
        .dateFromString(iso)
        ?: NSISO8601DateFormatter().apply {
            formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
        }.dateFromString(iso)

/**
 * One day of slack on each date bound. The authoritative compare is a *lexicographic* string compare in
 * `commonMain`; this predicate is an `NSDate` comparison. Where the two could disagree at a boundary
 * (fractional seconds, formatter rounding) the asymmetry matters: over-returning costs a few extra
 * round-trips and the admission drops them, while under-returning silently loses a photo nothing can add
 * back.
 */
private const val PREDICATE_WIDEN_SECONDS = 24.0 * 60.0 * 60.0

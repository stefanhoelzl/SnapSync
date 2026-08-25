package app.snapsync.rig.gallery

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.SelectionRule
import app.snapsync.ports.CandidateSource
import app.snapsync.rig.AssetView
import app.snapsync.rig.CensusView
import app.snapsync.rig.GalleryView
import app.snapsync.rig.PolicyView
import app.snapsync.rig.ResourceView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSPredicate
import platform.Photos.PHAsset
import platform.Photos.PHFetchOptions
import kotlin.time.TimeSource

/**
 * The bitmask values `PHAssetMediaSubtype` uses for the two origins the policy subtracts. Written as
 * literals because the census below uses the **SELECT** predicate form (`(mediaSubtypes & N) != 0`), not
 * the exclusion form the production fetch uses, and the point is to look for these directly.
 */
private const val SUBTYPE_SCREENSHOT = 4
private const val SUBTYPE_SCREEN_RECORDING = 524_288

/**
 * What the library holds, and what the selection policy would make of it.
 *
 * This replaced `SNAPSYNC_POLICY_PROBE`, and the change is not only where it is invoked from. The trigger
 * ran a refresh and **logged** a count, so reading the answer meant grepping `debug.log` and trusting that
 * the line you found belonged to the run you meant. This returns the answer, per asset, with the rule that
 * decided each one.
 *
 * ## Why the census does not go through the policy seam
 *
 * [census] fetches with **no predicate at all**, deliberately. The production fetch drops screenshots and
 * screen recordings *before* enumeration, so they never reach the count a policy-scoped read would see —
 * and those two counts are the only evidence that the subtype bits match real, OS-generated assets. A
 * synthesized library cannot demonstrate that: `PHAssetCreationRequest` cannot set a subtype. So the raw
 * numbers answer a question the policy-scoped read structurally cannot, which is why both exist.
 *
 * ## The cost ladder
 *
 * Facts are plain in-memory `PHAsset` properties and cost nothing. Resources are one synchronous
 * round-trip per asset (~110 ms on an SE2, so ~17 minutes across a 9525-asset library), which is why they
 * are opt-in and why the response reports what the read actually paid.
 *
 * ## Under a partial grant
 *
 * `total` is the hand-picked **selection**, not the library, because that is all PhotoKit returns. The
 * grant is reported beside it so `total: 12` on a 9525-photo phone reads as a small selection rather than a
 * small library. Note also that a fetch under `.limited` can surface iOS's own limited-access alert if the
 * library changed outside the selection since the app last looked — which is why this is its own route and
 * not a field of `/device/state`.
 */
class GalleryReader(
    private val candidates: CandidateSource,
    private val grant: () -> String,
) {

    suspend fun read(cutoff: String?, resources: Boolean): GalleryView {
        val census = census()
        if (cutoff == null) return GalleryView(census = census, grant = grant(), policy = null)

        val policy = SelectionPolicy(
            selectionRulesFor(
                includesUpload = true,
                cutoff = CaptureCutoff(CaptureDate(cutoff)),
                ceiling = null,
                // The operator reads the POLICY here, not the device's echo/album state — this route
                // answers "what would this cutoff admit", so the two port-read exclusions are out of scope.
                suppressedAssetIds = { emptySet() },
                albumExcludedAssetIds = { emptySet() },
            ),
        )
        val mark = TimeSource.Monotonic.markNow()
        val found = candidates.candidates(policy)
        val rules = policy.rules
        val assets = found.map { candidate ->
            val facts = candidate.facts
            // Admission comes from the POLICY — the single decision (capability
            // `photo-selection-policy`). This used to re-run the rule list itself and call the result
            // `admitted`, which is a second implementation of the one thing the policy exists to decide.
            // The rule scan below now only NAMES the reason, and never decides.
            val admitted = policy.admits(facts)
            // The FIRST rule that refuses is the reported reason. Reporting all of them would read as
            // "these all applied" when only one had to.
            val refusedBy = if (admitted) null else rules.firstOrNull { !it.admits(facts) }
            AssetView(
                assetId = facts.assetId,
                captureDate = facts.creationDate.iso,
                pixelArea = facts.pixelArea,
                isScreenshot = facts.isScreenshot,
                isScreenRecording = facts.isScreenRecording,
                isVideo = facts.isVideo,
                isEdited = facts.isEdited,
                admitted = admitted,
                refusedBy = refusedBy?.let(::describe),
                resources = if (resources && admitted) {
                    candidate.resources().map { ResourceView(it.contentType, it.filename) }
                } else {
                    null
                },
            )
        }
        return GalleryView(
            census = census,
            grant = grant(),
            policy = PolicyView(
                cutoff = cutoff,
                admitted = assets.count { it.admitted },
                excluded = assets.count { !it.admitted },
                readResources = resources,
                elapsedMs = mark.elapsedNow().inWholeMilliseconds,
                assets = assets,
            ),
        )
    }

    /**
     * Name the rule that refused, in the rule's own vocabulary rather than a re-description of it. A reader
     * who sees `MinImageArea(3000000)` can check the asset's `pixelArea` in the same row.
     */
    private fun describe(rule: SelectionRule): String = when (rule) {
        SelectionRule.DenyAll -> "DenyAll"
        is SelectionRule.CaptureAfter -> "CaptureAfter(${rule.cutoff.at.iso})"
        is SelectionRule.CaptureBefore -> "CaptureBefore(${rule.ceiling.at.iso})"
        SelectionRule.ExcludeScreenshots -> "ExcludeScreenshots"
        SelectionRule.ExcludeScreenRecordings -> "ExcludeScreenRecordings"
        is SelectionRule.MinImageArea -> "MinImageArea(${rule.minArea})"
        is SelectionRule.MinVideoArea -> "MinVideoArea(${rule.minArea})"
        is SelectionRule.NotEcho -> "NotEcho"
        is SelectionRule.NotInDenylistedAlbum -> "NotInDenylistedAlbum"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun census(): CensusView {
        val total = PHAsset.fetchAssetsWithOptions(null).count.toLong()
        return CensusView(
            total = total,
            screenshots = countMatching(SUBTYPE_SCREENSHOT),
            screenRecordings = countMatching(SUBTYPE_SCREEN_RECORDING),
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun countMatching(subtype: Int): Long {
        val options = PHFetchOptions()
        options.predicate = NSPredicate.predicateWithFormat("(mediaSubtypes & $subtype) != 0", argumentArray = null)
        return PHAsset.fetchAssetsWithOptions(options).count.toLong()
    }
}

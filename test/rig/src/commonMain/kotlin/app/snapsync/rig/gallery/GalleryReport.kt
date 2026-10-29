package app.snapsync.rig.gallery

import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.EventWindow
import app.snapsync.domain.sharing.AdmissionVerdict
import app.snapsync.domain.sharing.CandidateSet
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.DenyAll
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.ImageFloor
import app.snapsync.domain.sharing.InRange
import app.snapsync.domain.sharing.NoScreenRecordings
import app.snapsync.domain.sharing.NoScreenshots
import app.snapsync.domain.sharing.SelectionCalibration
import app.snapsync.domain.sharing.SelectionRule
import app.snapsync.domain.sharing.VideoFloor
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText
import app.snapsync.levers.AssetView
import app.snapsync.levers.CalibrationView
import app.snapsync.levers.CensusView
import app.snapsync.levers.GalleryView
import app.snapsync.levers.PolicyView
import app.snapsync.levers.ResourceView
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.Resource
import app.snapsync.ports.GalleryRead
import app.snapsync.services.gallery.resourcesFrom
import kotlin.time.TimeSource

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
 * The app host's census (`photoKitCensus`) fetches with **no predicate at all**, deliberately. The production fetch drops screenshots and
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
 * not a field of `host.state()`.
 */
class GalleryReport(
    /** The app's own photos — the same read and policy the app takes, never a second walk. */
    private val photos: OwnPhotos,
    private val grant: () -> String,
    /**
     * The raw subtype census, from the host's own library. Injected because it is the one part of this read
     * that is platform by nature: the app host counts PhotoKit's subtype bitmask, the JVM host its world
     * gallery. Everything else here is the app's own read and selection policy, on both hosts.
     */
    private val census: () -> CensusView,
) {

    suspend fun read(cutoff: String?, resources: Boolean, includesUpload: Boolean = true): GalleryView {
        val census = census()
        if (cutoff == null) return GalleryView(census = census, grant = grant(), policy = null)

        // What this cutoff would admit, over the longest range a member may choose from it. A non-contributing
        // membership is a policy this route must be able to READ too: the deny-everything narrowing is what keeps a
        // download-only member off a whole-library walk. The operator reads the POLICY here, not the device's echo
        // state, so the echo set is empty; the album denylist is read as the app reads it.
        val range = longestRangeFrom(CaptureCutoff(TimestampText.parse(cutoff)))
        val direction = if (includesUpload) Direction.UploadOnly else Direction.DownloadOnly
        val contribution = photos.contribution(direction, range, received = emptySet())
        val mark = TimeSource.Monotonic.markNow()
        // The read the app makes, so this reports what the app would see — including "there is nothing to report".
        // Answering that with an empty asset list would tell an operator the library is empty when the truth is that
        // nobody could look.
        val read = photos.read(contribution)
        val (facts, selected) = when (val set = read.candidates) {
            is CandidateSet.Walked -> set.assets to false
            is CandidateSet.Selected -> set.assets to true
            CandidateSet.NotReadable ->
                return GalleryView(census = census, grant = grant(), policy = null, notReadable = true)
        }
        // Admission comes from the POLICY — the single decision. The rule scan below only NAMES the reason.
        val admitted = when (val verdict = contribution.policy.admit(read.candidates)) {
            is AdmissionVerdict.Discovered -> verdict.discovery.admitted.mapTo(mutableSetOf()) { it.facts.assetId }
            AdmissionVerdict.NotReadable -> emptySet()
        }
        val held = if (resources) heldResources(read.resources, facts) else null
        val rules = rulesOf(range, includesUpload, selected)
        val assets = facts.map {
            view(
                it,
                it.assetId in admitted,
                rules,
                held?.get(it.assetId).orEmpty().takeIf { resources },
            )
        }
        return GalleryView(
            census = census,
            grant = grant(),
            policy = PolicyView(
                cutoff = cutoff,
                calibration = SelectionCalibration.STANDARD.let {
                    CalibrationView(it.imageFloor.pixels, it.videoFloor.pixels, it.denylist.sorted())
                },
                admitted = assets.count { it.admitted },
                excluded = assets.count { !it.admitted },
                readResources = resources,
                elapsedMs = mark.elapsedNow().inWholeMilliseconds,
                assets = assets,
            ),
        )
    }

    /** Every candidate's resources, in one request — the cost ladder's expensive rung, paid only on request. */
    private suspend fun heldResources(
        of: app.snapsync.data.sharing.AssetResources,
        facts: List<AssetFacts>,
    ): Map<AssetId, List<Resource>> = when (val read = of.of(facts.mapTo(linkedSetOf()) { it.assetId })) {
        is GalleryRead.Read -> resourcesFrom(read.value).groupBy { it.assetId }
        GalleryRead.NotReadable -> emptyMap()
    }

    private fun view(asset: AssetFacts, admitted: Boolean, rules: List<SelectionRule>, held: List<Resource>?) = AssetView(
        assetId = asset.assetId.value,
        captureDate = TimestampText.captureText(asset.captureDate),
        pixelArea = (asset.pixelArea as? PixelArea.Known)?.pixels,
        isScreenshot = asset.kind == MediaKind.SCREENSHOT,
        isScreenRecording = asset.kind == MediaKind.SCREEN_RECORDING,
        isVideo = asset.kind.isMoving,
        isEdited = asset.isEdited,
        admitted = admitted,
        // The FIRST rule that refuses is the reported reason; reporting all of them would read as "these all applied".
        // The album denylist is the one rule whose ids this report does not hold, so it is the reason left over.
        refusedBy = if (admitted) null else rules.firstOrNull { !it.admits(asset) }?.let(::describe) ?: DENYLISTED,
        resources = held?.takeIf { admitted }?.map { ResourceView(it.contentType, it.filename) },
        originalFilenames = held?.mapNotNull { it.metadata[RESOURCE_META_ORIGINAL_FILENAME] },
    )

    /**
     * The policy's rules, in its order, for naming a refusal — never for deciding one: the policy decides. A selection
     * is judged without the rules about what a photo is.
     */
    private fun rulesOf(range: CaptureRange, includesUpload: Boolean, selected: Boolean): List<SelectionRule> =
        if (!includesUpload) {
            listOf(DenyAll)
        } else {
            listOf(
                InRange(range),
                NoScreenshots,
                NoScreenRecordings,
                ImageFloor(SelectionCalibration.STANDARD.imageFloor),
                VideoFloor(SelectionCalibration.STANDARD.videoFloor),
            ).filterNot { selected && it.judgesWhatItIs }
        }

    /** Name the rule that refused, in the rule's own vocabulary — a reader can check the asset's facts in the same row. */
    private fun describe(rule: SelectionRule): String = when (rule) {
        is InRange -> "InRange(${TimestampText.format(
            rule.range.from.at,
        )}..${TimestampText.format(rule.range.until.at)})"
        is ImageFloor -> "ImageFloor(${SelectionCalibration.STANDARD.imageFloor.pixels})"
        is VideoFloor -> "VideoFloor(${SelectionCalibration.STANDARD.videoFloor.pixels})"
        else -> rule::class.simpleName.orEmpty()
    }

    private companion object {
        const val DENYLISTED = "NotInDenylistedAlbum"

        /** The longest range a member may choose from [from]: the event it would be is that range itself. */
        fun longestRangeFrom(from: CaptureCutoff): CaptureRange {
            val until = Timestamp.of(from.at.at + EventWindow.MAX)
            val verdict = CaptureRange.of(from, CaptureCeiling(until), EventStart(from.at), EventEnd(until))
            return (verdict as CaptureRangeVerdict.Ok).range
        }
    }
}

package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.RecordedAsset
import app.snapsync.domain.time.Timestamp

/**
 * The **selection policy**: what a membership contributes — the one decision every consumer of a member's photos takes
 * (the byte upload, the device manifest, the status total, the join preview, the album gather), so none can judge a
 * photo another way.
 *
 * Built only by [of], from the membership's values; a policy is a conjunction of rules, and the derivation is where its
 * invariants live: a contributing membership always has its range, and one that contributes nothing has
 * [DenyAll] alone. It decides two kinds of input, by type: a read's candidates ([admit], minting [AdmittedAsset]) and
 * ledger rows ([admitRecorded], minting [AdmittedRow]).
 */
class SelectionPolicy private constructor(
    private val rules: List<SelectionRule>,
    /** The capture dates a walk may narrow its read to — [WalkBounds.None] when the policy admits nothing. */
    val walkBounds: WalkBounds,
) {

    /** Whether this membership contributes at all — its direction includes upload. */
    val contributes: Boolean get() = walkBounds is WalkBounds.Within

    /**
     * [set], admitted: every candidate all rules admit — or, for a hand-picked selection, every candidate the rules
     * that do not judge what a photo is admit (the member chose each one). The rules follow where the candidates came
     * from, never a second look at the grant, so a library walk can never be judged as a selection.
     */
    fun admit(set: CandidateSet): AdmissionVerdict = when (set) {
        CandidateSet.NotReadable -> AdmissionVerdict.NotReadable
        is CandidateSet.Walked -> admitted(set.assets, set.takenAt, DiscoverySource.LIBRARY, rules)
        is CandidateSet.Selected ->
            admitted(set.assets, set.takenAt, DiscoverySource.SELECTION, rules.filterNot { it.judgesWhatItIs })
    }

    /**
     * The [rows] this policy admits. A row records that the policy admitted its asset *when it was written*, and a
     * policy changes under it (a raised cutoff), so a row is asked again rather than trusted. Only the rules that need
     * no more than an id and a capture date speak; a row with no capture date is not admitted.
     */
    @OptIn(MintsAdmittedRow::class)
    fun admitRecorded(rows: List<RecordedAsset>): List<AdmittedRow> {
        val byRows = rules.filterIsInstance<RecordedRule>()
        return rows.filter { row -> byRows.all { it.admits(row) } }.map { AdmittedRow(it.assetId) }
    }

    @OptIn(MintsAdmittedAsset::class)
    private fun admitted(
        assets: List<AssetFacts>,
        takenAt: Timestamp,
        source: DiscoverySource,
        by: List<SelectionRule>,
    ): AdmissionVerdict.Discovered = AdmissionVerdict.Discovered(
        Discovery(
            admitted = assets.filter { asset -> by.all { it.admits(asset) } }.map(::AdmittedAsset),
            takenAt = takenAt,
            source = source,
            considered = assets.size,
        ),
    )

    companion object {
        /**
         * The one derivation. A membership whose direction excludes upload contributes nothing ([DenyAll]); every other
         * is judged by its range and every exclusion — the echo and album sets always present, an empty one admitting
         * all.
         */
        fun of(inputs: SelectionInputs): SelectionPolicy = when (inputs.direction.includesUpload) {
            false -> SelectionPolicy(listOf(DenyAll), WalkBounds.None)
            true -> SelectionPolicy(contributing(inputs), WalkBounds.Within(inputs.range))
        }

        private fun contributing(inputs: SelectionInputs): List<SelectionRule> = listOf(
            InRange(inputs.range),
            NoScreenshots,
            NoScreenRecordings,
            ImageFloor(SelectionCalibration.STANDARD.imageFloor),
            VideoFloor(SelectionCalibration.STANDARD.videoFloor),
            NotEcho(inputs.received),
            NotInDenylistedAlbum(inputs.inDeniedAlbums),
        )
    }
}

/**
 * What a policy is derived from. [range] is the range as it is shared now — a membership's
 * ([CaptureRange.sharedAt]) or the one a preview counts.
 */
class SelectionInputs(
    val direction: Direction,
    val range: CaptureRange,
    /** Photos this device received through SnapSync — echo suppression. */
    val received: Set<AssetId>,
    /** Photos in a denylisted album; empty unless the library itself is walked. */
    val inDeniedAlbums: Set<AssetId>,
)

/** What a walk of the library may be narrowed to. */
sealed interface WalkBounds {
    /** The policy admits nothing: nothing is read. */
    data object None : WalkBounds

    /** The capture dates of [range]; a walk may return more, never less. */
    data class Within(val range: CaptureRange) : WalkBounds
}

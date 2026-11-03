package app.snapsync.domain.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.AdmittedRow

/**
 * **Which rows a top-up tries**, in key order: the rows needing a job whose asset the membership's policy admits now. A
 * row records that the policy admitted its asset when it was written, and the policy changes under it — a member who
 * raises their cutoff leaves rows the current policy excludes — so the rows are judged again, by the same admission
 * the device manifest projects through: what leaves the device and what it declares cannot disagree.
 *
 * Unbounded: the platform's refusal bounds what is created, never what is planned — a bound here would starve the
 * admitted rows sorting behind excluded ones.
 */
class TopUpPlan private constructor(val rows: List<UploadRow.Discovered>) {
    /** The photos this top-up may create jobs for — placed in the event album before any of their jobs exists. */
    val assets: Set<AssetId> get() = rows.mapTo(mutableSetOf()) { it.key.asset }

    companion object {
        /** The [needing] rows whose asset is among [admitted]. */
        fun of(needing: List<UploadRow.Discovered>, admitted: List<AdmittedRow>): TopUpPlan {
            val ids = admitted.mapTo(mutableSetOf()) { it.assetId }
            return TopUpPlan(needing.filter { it.key.asset in ids })
        }
    }
}

package app.snapsync.model

/**
 * The assets a membership's [policy] **admits** among a set of ledger [rows] (capability
 * `photo-selection-policy`) — the one derivation both consumers of the ledger take: the device-manifest
 * projection ([projectDeviceManifest]), which declares what this device provides, and the upload cycle's
 * enqueue, which decides whose bytes leave.
 *
 * **A ledger row is not the admitted set.** It records that the policy admitted its asset *when the row
 * was written*, and a membership's policy changes under it (capability `reconfigure-membership`): a
 * member who raises their cutoff leaves rows behind that the current policy excludes. Reading rows and
 * acting on them without asking is what the policy's *no consumer SHALL treat an upstream-filtered
 * structure as the admitted set* forbids — and it shipped once, as an uploader that kept sending the
 * photos a narrowing had just excluded while the manifest, which does ask, stopped listing them.
 *
 * A row carries a date and an id but not the origin facts (a screenshot earns no row at all — the cycle
 * drops it before recording), so those default to **admit-on-doubt**: the rules that can still speak here
 * are the two capture-date bounds and the two id-set exclusions, which is exactly what a per-event
 * question about a device-global ledger needs. An empty `creationDate` sorts before every real cutoff, so
 * a row the policy cannot judge is excluded — the one place a missing fact excludes rather than admits —
 * and the next walk that reaches it fills its detail before advancing the cursor, so it re-enters with a
 * real date.
 *
 * Rows are grouped per asset first: several resources of one photo share an `assetId` and stand or fall
 * together, or a Live Photo's paired video outlives its excluded primary as an orphan.
 */
suspend fun admittedAssetIds(rows: Collection<LedgerEntry>, policy: SelectionPolicy): Set<String> {
    val facts = rows.groupBy { it.assetId }.map { (assetId, group) ->
        AssetFacts(assetId = assetId, creationDate = CaptureDate(group.first().creationDate))
    }
    return EventPhotoSet(policy) { candidatesFromFacts(facts) }
        .assets()
        .mapTo(mutableSetOf()) { it.facts.assetId }
}

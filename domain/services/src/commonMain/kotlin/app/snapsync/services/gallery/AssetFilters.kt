package app.snapsync.services.gallery

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.ports.AssetFilter

/**
 * The gallery read [this] policy asks for: its capture range and nothing else, the platform's hint to narrow by — or
 * `null` for a policy that admits nothing, which reads nothing at all: its answer is an empty read, as authoritative as
 * any other. The other rules are applied over what comes back, by the one admission.
 */
internal fun SelectionPolicy.assetFilter(): AssetFilter? {
    if (rules.any { it.deniesEverything }) return null
    return AssetFilter(
        createdFrom = rules.filterIsInstance<SelectionRule.CaptureAfter>().map { it.cutoff.at.at }.maxOrNull(),
        createdUntil = rules.filterIsInstance<SelectionRule.CaptureBefore>().map { it.ceiling.at.at }.minOrNull(),
    )
}

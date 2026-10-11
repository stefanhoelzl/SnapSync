package app.snapsync.services.gallery

import app.snapsync.model.CaptureDate
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.ports.AssetFilter
import kotlin.time.Instant

/**
 * The gallery read [this] policy asks for: its capture range and nothing else, the platform's hint to narrow by — or
 * `null` for a policy that admits nothing, which reads nothing at all: its answer is an empty read, as authoritative as
 * any other. The other rules are applied over what comes back, by the one admission.
 *
 * A bound that does not parse is left open: the read is then wider, never narrower.
 */
internal fun SelectionPolicy.assetFilter(): AssetFilter? {
    if (rules.any { it.deniesEverything }) return null
    return AssetFilter(
        createdFrom = rules.filterIsInstance<SelectionRule.CaptureAfter>().mapNotNull { it.cutoff.at.instant() }.maxOrNull(),
        createdUntil = rules.filterIsInstance<SelectionRule.CaptureBefore>().mapNotNull { it.ceiling.at.instant() }.minOrNull(),
    )
}

/** [this] date as an instant, or `null` for one that is absent or does not parse. */
internal fun CaptureDate.instant(): Instant? = Instant.parseOrNull(iso)

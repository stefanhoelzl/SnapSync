package app.snapsync.feature.upload

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.SelectionScope
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.Discovery

/**
 * The read-discipline gate on upload discovery (capability `limited-photo-access`): under a partial
 * grant, discovery reads the current selection snapshot instead of walking the library.
 *
 * Wraps the platform [BackgroundTransfer] inside the ONE shared cycle assembly (`uploadCore`), so
 * every tier and the world get it identically; the cycle itself stays discovery-source-blind. The
 * decision input is the injected [selectionScope] — derived by the composition from the current
 * permission and the latest snapshot — so this class holds no policy of its own:
 *
 * - [SelectionScope.Unrestricted] → delegate to the platform walk, unchanged.
 * - [SelectionScope.Scoped] → return the snapshot as the discovery, **without any platform read**.
 *   The walk cursor is preserved (`nextToken` = the incoming token), so a later full-access walk
 *   resumes incrementally rather than from scratch. `fullEnumeration` stays false: a selection
 *   snapshot is not the whole-library key-set, so it must never drive ledger pruning — an uploaded,
 *   later-deselected photo keeps its `COMPLETED` row (deselection is not withdrawal; upload is a
 *   publish).
 */
class SelectionScopedTransfer(
    private val delegate: BackgroundTransfer,
    private val selectionScope: () -> SelectionScope,
) : BackgroundTransfer by delegate {

    override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery =
        when (val scope = selectionScope()) {
            SelectionScope.Unrestricted -> delegate.discoverResources(sinceToken, policy)
            is SelectionScope.Scoped -> Discovery(
                // The snapshot arrives already read, with resources — the sanctioned eager read is what
                // keeps every library FETCH in-flow (capability `limited-photo-access`). Wrapping it as
                // held candidates is honest: they genuinely are in hand, so nothing is deferred and
                // nothing will need re-fetching by identifier later.
                candidates = candidatesFromResources(scope.resources),
                nextToken = sinceToken ?: ByteArray(0),
                removedAssetIds = emptyList(),
                fullEnumeration = false,
            )
        }
}

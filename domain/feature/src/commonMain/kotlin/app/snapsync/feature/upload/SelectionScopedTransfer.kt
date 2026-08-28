package app.snapsync.feature.upload

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.Resource
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

    /**
     * The same read discipline applied to the ledger-driven resolve (capability `sync-ledger`): under a
     * partial grant the selection snapshot IS this membership's own-photo scope, so the keys are answered
     * **from the snapshot already in hand** and no platform read happens.
     *
     * A key the snapshot does not carry resolves to nothing, which is the port's contract and the honest
     * answer here: under `.limited` a photo outside the user's selection is not this app's to upload, and
     * that is the same absence as an asset having left the library — the caller stops asking for it either
     * way.
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
        when (val scope = selectionScope()) {
            SelectionScope.Unrestricted -> delegate.resourcesFor(keys)
            is SelectionScope.Scoped -> scope.resources.filter { it.filename in keys }
        }

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

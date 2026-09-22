package app.snapsync.feature.upload

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.ports.Discovery
import app.snapsync.ports.UploadDiscovery

/**
 * The read-discipline gate on upload discovery (capability `limited-photo-access`): under a partial
 * grant, discovery reads the current selection snapshot instead of walking the library.
 *
 * Wraps the cycle's [UploadDiscovery] inside the ONE shared cycle assembly (`uploadCore`), so every tier
 * and the world get it identically; the cycle itself stays discovery-source-blind. The decision input is
 * the injected [selectionScope] — derived by the composition from the current permission and the latest
 * snapshot — so this class holds no policy of its own:
 *
 * - [SelectionScope.Unrestricted] → delegate to the platform walk, unchanged.
 * - [SelectionScope.Scoped] → return the snapshot as the discovery, **without any platform read**, and
 *   **authoritative** (`fullEnumeration = true`): under a partial grant the selection IS the gallery, so a
 *   photo the snapshot no longer carries has left it, exactly as a photo a library walk no longer returns
 *   has — de-selecting is deleting, and its rows go (capability `sync-ledger`, "Deletion is a presence diff
 *   over an authoritative walk").
 * - [SelectionScope.Unread] → **refuse**, on both reads. The app holds no selection yet, and every answer
 *   this class could give deletes: an authoritative empty discovery says every photo left, and an empty
 *   resolution says every row's asset is gone. The app's cycle is withheld while the scope is unread
 *   (`appAdmission`), so this is a backstop — a throw fails one cycle, an answer loses rows.
 *
 * Decision record: `changes/selection-is-the-walk` (D1), which made a read snapshot authoritative.
 *
 * It wraps the library reads and nothing else. Free capacity, job creation and the terminal drain are the
 * transport's facts, and a partial photo grant changes what may be READ, never what a transport will accept.
 */
class SelectionScopedDiscovery(
    private val delegate: UploadDiscovery,
    private val selectionScope: () -> SelectionScope,
) : UploadDiscovery {

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
            SelectionScope.Unread -> refuseUnread("resolve ${keys.size} key(s)")
        }

    override suspend fun discover(policy: SelectionPolicy): Discovery =
        when (val scope = selectionScope()) {
            SelectionScope.Unrestricted -> delegate.discover(policy)
            is SelectionScope.Scoped -> Discovery(
                // The snapshot arrives already read, with resources — the sanctioned eager read is what
                // keeps every library FETCH in-flow (capability `limited-photo-access`). Wrapping it as
                // held candidates is honest: they genuinely are in hand, so nothing is deferred and
                // nothing will need re-fetching by identifier later.
                candidates = candidatesFromResources(scope.resources),
                fullEnumeration = true,
            )
            SelectionScope.Unread -> refuseUnread("discover")
        }

    private fun refuseUnread(what: String): Nothing =
        throw IllegalStateException(
            "cannot $what: the partial grant's selection has not been read yet — an unread selection is not " +
                "an empty one, and answering it as empty would delete rows",
        )
}

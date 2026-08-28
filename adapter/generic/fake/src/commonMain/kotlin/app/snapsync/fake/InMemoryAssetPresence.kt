package app.snapsync.fake

import app.snapsync.model.AssetPresence
import app.snapsync.ports.ImportedAssetPresence
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The honest in-memory [ImportedAssetPresence]: a library that holds [present] identifiers, and a
 * [readable] cell saying whether it can be seen at all.
 *
 * Two cells rather than one because the port's three verdicts collapse to two questions — *is it there*
 * and *may I look* — and the second is what a partial or revoked photo grant takes away. With
 * [readable] false every answer is `UNKNOWN`, which is the state that must never be mistaken for
 * `ABSENT`: acting on a false absence clears a live marker and orphans a real photo.
 *
 * State arrives by constructor, per the fake-honesty rule; the cells are mutable so a test can move the
 * library under a running subject. Levers and inspection belong in `:test:world` wrappers, not here.
 */
internal class InMemoryAssetPresence(
    private val present: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    private val readable: MutableStateFlow<Boolean> = MutableStateFlow(true),
) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> =
        if (!readable.value) {
            localIds.associateWith { AssetPresence.UNKNOWN }
        } else {
            val library = present.value
            localIds.associateWith {
                if (it in library) AssetPresence.PRESENT else AssetPresence.ABSENT
            }
        }
}

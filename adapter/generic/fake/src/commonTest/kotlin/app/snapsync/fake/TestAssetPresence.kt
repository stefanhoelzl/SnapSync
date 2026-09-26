package app.snapsync.fake

import app.snapsync.model.AssetPresence
import app.snapsync.ports.ImportedAssetPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A presence answer for the download feature's tests: a library holding [present] identifiers, and a [readable] cell
 * saying whether it can be seen at all. Not an adapter — presence is a service over the gallery
 * (`GalleryAssetPresence`), held to its own tests — but the two questions its verdicts collapse to, as cells a test
 * can move under a running subject. With [readable] false every answer is `UNKNOWN`, the state that must never be
 * mistaken for `ABSENT`.
 */
internal class InMemoryAssetPresence(
    private val present: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    private val readable: StateFlow<Boolean> = MutableStateFlow(true),
) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> =
        if (!readable.value) {
            localIds.associateWith { AssetPresence.UNKNOWN }
        } else {
            localIds.associateWith { if (it in present.value) AssetPresence.PRESENT else AssetPresence.ABSENT }
        }
}

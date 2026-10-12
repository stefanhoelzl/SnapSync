package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.RawAsset
import app.snapsync.ports.Gallery
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The photo-library fakes' factories: the same rule as `Factories.kt` (each returns the PORT type over an
// `internal` class, taking initial state), kept in their own file so neither file outgrows its measured
// surface (`docs/architecture.md`).

/**
 * The gallery over the caller's own cells: [library] (the assets; an import that lands adds its asset here),
 * [access] (the grant), and
 * [userAlbums] (the albums other apps made, title → asset ids). [answer] is what the member will choose if
 * asked while undetermined; [answers] how the library answers each import's change (the default always succeeds).
 */
fun inMemoryGallery(
    library: MutableStateFlow<List<RawAsset>>,
    access: MutableStateFlow<LibraryGrant> = MutableStateFlow(LibraryGrant.GRANTED),
    userAlbums: StateFlow<Map<String, Set<AssetId>>> = MutableStateFlow(emptyMap()),
    answer: LibraryGrant = LibraryGrant.GRANTED,
    answers: LibraryChangeAnswers = LibraryChangeAnswers.Ordinary,
): Gallery = InMemoryGallery(LibraryState(library, access, userAlbums, answer, answers))

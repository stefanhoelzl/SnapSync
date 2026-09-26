package app.snapsync.fake

import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.ports.Gallery
import app.snapsync.model.AssetRef
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoLibraryImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The photo-library fakes' factories: the same rule as `Factories.kt` (each returns the PORT type over an
// `internal` class, taking initial state), kept in their own file so neither file outgrows its measured
// surface (`docs/architecture.md`).

/**
 * The gallery over the caller's own cells: [library] (the assets), [access] (the grant — share it with
 * [inMemoryPhotoAccess] so the status source and the gallery agree), and [userAlbums] (the albums other apps
 * made, title → normalized asset ids). [answer] is what the member will choose if asked while undetermined.
 */
fun inMemoryGallery(
    library: StateFlow<List<RawAsset>>,
    access: MutableStateFlow<GalleryAccess> = MutableStateFlow(GalleryAccess.GRANTED),
    userAlbums: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap()),
    answer: GalleryAccess = GalleryAccess.GRANTED,
): Gallery = InMemoryGallery(library, access, userAlbums, answer)

/**
 * The photo-permission adapter over the caller's own [status] cell, as both of its ports at once — one adapter
 * implements both on every platform.
 */
fun inMemoryPhotoAccess(
    status: MutableStateFlow<GalleryAccess>,
): Pair<PhotoAccessStatusSource, PhotoAccessRequester> =
    InMemoryPhotoAccess(status).let { it to it }

/**
 * The importer over the caller's own [library] cell, recording markers through the same three collaborators
 * the real adapter takes. [answers] is how the library answers each change; the default always succeeds.
 */
fun inMemoryPhotoLibraryImporter(
    library: MutableStateFlow<List<RawAsset>>,
    recordCreatedLocalId: (AssetRef, String) -> Boolean,
    clearCreatedLocalId: (AssetRef, String) -> Unit,
    confirmCreatedLocalId: (AssetRef, String) -> Unit,
    answers: LibraryChangeAnswers = LibraryChangeAnswers.Ordinary,
): PhotoLibraryImporter =
    InMemoryPhotoLibraryImporter(library, recordCreatedLocalId, clearCreatedLocalId, confirmCreatedLocalId, answers)

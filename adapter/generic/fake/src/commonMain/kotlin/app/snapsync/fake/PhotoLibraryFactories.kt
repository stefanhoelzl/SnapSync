package app.snapsync.fake

import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.ports.AlbumManager
import app.snapsync.model.AssetRef
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.UploadDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The photo-library fakes' factories: the same rule as `Factories.kt` (each returns the PORT type over an
// `internal` class, taking initial state), kept in their own file so neither file outgrows its measured
// surface (`docs/architecture.md`).

/**
 * The upload cycle's library reads over [source] (the walk) and [library] (the unscoped contents a fetch by
 * identifier reads). Both are the caller's own: the world passes its gallery's candidate source and cell.
 * [grant] is the process's photo grant; a walk is authoritative only under a full one.
 */
fun inMemoryUploadDiscovery(
    source: CandidateSource,
    library: StateFlow<List<RawAsset>>,
    grant: () -> PermissionStatus = { PermissionStatus.GRANTED },
): UploadDiscovery = InMemoryUploadDiscovery(source, library, grant)

/** The library's change token over the caller's own [library] cell: any change to the cell moves it. */
fun inMemoryLibraryChangeTokenRead(library: StateFlow<List<RawAsset>>): LibraryChangeTokenRead =
    InMemoryLibraryChangeTokenRead(library)

/**
 * Albums over the caller's own [library] cell. [userAlbums] is also the caller's: the albums other apps made,
 * title → normalized asset ids.
 */
fun inMemoryAlbumManager(
    library: StateFlow<List<RawAsset>>,
    userAlbums: MutableStateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap()),
): AlbumManager = InMemoryAlbumManager(library, userAlbums)

/**
 * The photo-access adapter over the caller's own [status] cell, as both of its ports at once — one adapter
 * implements both on every platform. [answer] is what the user will choose if asked while undetermined.
 */
fun inMemoryPhotoAccess(
    status: MutableStateFlow<PermissionStatus>,
    answer: PermissionStatus = PermissionStatus.GRANTED,
): Pair<PhotoAccessStatusSource, PhotoAccessRequester> =
    InMemoryPhotoAccess(status, answer).let { it to it }

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

/**
 * Presence over the caller's own [library] cell: an asset is present while the library holds an asset with
 * that normalized id. [readable] is the grant's other question — with it false every answer is `UNKNOWN`.
 */
fun inMemoryLibraryPresence(
    library: StateFlow<List<RawAsset>>,
    readable: StateFlow<Boolean>,
): ImportedAssetPresence = InMemoryAssetPresence({ library.value.mapTo(mutableSetOf()) { it.assetId } }, readable)

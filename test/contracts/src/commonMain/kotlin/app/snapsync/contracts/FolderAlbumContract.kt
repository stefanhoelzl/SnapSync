package app.snapsync.contracts

import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.GalleryRead
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.StagedResource
import app.snapsync.model.WriteOutcome
import app.snapsync.model.captureCutoff
import app.snapsync.ports.Gallery
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states a folder-album library can be found in, as far as a clause cares. */
enum class FolderAlbumState {
    /**
     * A full grant, on a platform whose albums are the folders photos live in (Android), and [SEED_COUNT] photos seeded in
     * the clause's window in the camera folder, each one this app saved itself — so one it may move. iOS has no folders.
     */
    GRANTED_OWN_PHOTOS_SEEDED,
}

/**
 * A folder-album library as a clause receives it: the gallery, the [seeded] own photos, and a way to [stage] a fresh
 * photo for an import (a library takes the staged file when it ingests it).
 */
class FolderAlbums(val gallery: Gallery, val seeded: Set<AssetId>, val stage: () -> List<StagedResource>)

/**
 * What a [AlbumKind.FOLDER] gallery promises about the event album (capability `event-album`; decision record
 * `changes/archive/2026-09-30-android-event-album` D2, D3, D6, D8) — this list IS the specification of those
 * obligations. The core
 * builds on each: an empty album that resolved would never read as deleted; a move that minted a new id would let echo
 * suppression miss a received photo; an album photo that stayed a candidate would be shared back as the member's own.
 */
object FolderAlbumContract : Contract<FolderAlbumState, FolderAlbums>("FolderAlbum") {

    /** The album title a clause creates. Unique per clause, so a shared library cannot confuse two. */
    fun title(clauseId: String) = "snapsync-contract-$clauseId"

    private fun start(clauseId: String) = captureCutoff(PhotoLibrary.window(name, clauseId).start)

    override val clauses = clauses {

        clause("A_FOLDER_LIBRARY_DECLARES_IT", FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED) { subject ->
            assertEquals(AlbumKind.FOLDER, subject.gallery.albumKind, "a library whose albums are folders says so")
        }

        clause("TWO_ALBUMS_OF_ONE_TITLE_ARE_TWO_FOLDERS", FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED) { subject ->
            val title = title("TWO_ALBUMS_OF_ONE_TITLE_ARE_TWO_FOLDERS")
            val first = assertNotNull(subject.gallery.createAlbum(title))
            val second = assertNotNull(subject.gallery.createAlbum(title))
            assertNotEquals(first, second, "two events of one name never share a folder, even while the first is empty")
        }

        clause("AN_EMPTY_ALBUM_RESOLVES_ONCE_A_PHOTO_LANDS", FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED) { subject ->
            val clauseId = "AN_EMPTY_ALBUM_RESOLVES_ONCE_A_PHOTO_LANDS"
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            assertEquals(
                GalleryRead.Read(emptyList()),
                subject.gallery.albumsById(setOf(album)),
                "an empty folder is no album, so the core cannot read a fresh one as present",
            )
            assertEquals(WriteOutcome.Ok, subject.gallery.addToAlbum(album, subject.seeded), "an empty album takes photos")
            val byId = assertIs<GalleryRead.Read<List<AlbumRecord>>>(subject.gallery.albumsById(setOf(album)))
            assertEquals(listOf(album), byId.value.map { it.id })
            assertEquals(GalleryRead.Read(subject.seeded), subject.gallery.albumMembers(album, start(clauseId)))
        }

        clause("A_MOVED_PHOTO_KEEPS_ITS_ID_AND_IS_NO_CANDIDATE", FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED) { subject ->
            val clauseId = "A_MOVED_PHOTO_KEEPS_ITS_ID_AND_IS_NO_CANDIDATE"
            val policy = PhotoLibrary.policy(name, clauseId)
            val before = assertIs<GalleryRead.Read<List<AssetFacts>>>(subject.gallery.assets(policy))
            assertEquals(subject.seeded, before.value.mapTo(mutableSetOf()) { it.assetId } intersect subject.seeded)
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            val missing = absentAssetId(clauseId)
            assertEquals(WriteOutcome.Ok, subject.gallery.addToAlbum(album, subject.seeded + missing), "a missing id is skipped")
            assertEquals(WriteOutcome.Ok, subject.gallery.addToAlbum(album, subject.seeded), "a second move is a no-op")
            val byId = assertIs<GalleryRead.Read<List<AssetFacts>>>(subject.gallery.assetsById(subject.seeded))
            assertEquals(subject.seeded, byId.value.mapTo(mutableSetOf()) { it.assetId }, "each photo keeps its id")
            assertEquals(SEED_COUNT, byId.value.size, "each photo is in the library once")
            val after = assertIs<GalleryRead.Read<List<AssetFacts>>>(subject.gallery.assets(policy))
            val leaked = after.value.mapTo(mutableSetOf()) { it.assetId } intersect subject.seeded
            assertTrue(leaked.isEmpty(), "a photo in an event album is never a candidate to share: $leaked")
        }

        clause("AN_IMPORT_INTO_AN_ALBUM_LANDS_THERE", FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED) { subject ->
            val clauseId = "AN_IMPORT_INTO_AN_ALBUM_LANDS_THERE"
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            val ref = AssetRef(sourceDeviceId = "contract-device", sourceAssetId = AssetId(clauseId))
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.gallery.import(ImportRequest(ref, subject.stage(), window.seedDate, album))
            val id = assertIs<ImportResult.Imported>(result, "an ordinary photo imports").createdLocalId
            val members = assertIs<GalleryRead.Read<Set<AssetId>>>(subject.gallery.albumMembers(album, null))
            assertEquals(setOf(id), members.value, "the received photo is in the album the moment it exists")
            val byId = assertIs<GalleryRead.Read<List<AssetFacts>>>(subject.gallery.assetsById(setOf(id)))
            assertEquals(1, byId.value.size, "and in the library once")
        }
    }
}

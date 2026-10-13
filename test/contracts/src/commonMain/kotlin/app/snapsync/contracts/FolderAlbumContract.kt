package app.snapsync.contracts

import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/** The states a folder-album library can be found in, as far as a clause cares. */
enum class FolderAlbumState {
    /**
     * A full grant, on a platform whose albums are the folders photos live in (Android), and [SEED_COUNT] photos seeded in
     * the clause's window in the camera folder, each one this app saved itself — so one it may move. iOS has no folders.
     */
    GRANTED_OWN_PHOTOS_SEEDED,
}

/**
 * A folder-album library as a clause receives it: the gallery, the [seeded] own photos, the [ownFolder] they live in —
 * the member's camera folder, no event album — and a way to [stage] a fresh photo for an import (a library takes the
 * staged file when it ingests it).
 */
class FolderAlbums(
    val gallery: Gallery,
    val seeded: Set<PlatformAssetId>,
    val ownFolder: PlatformAlbumId,
    val stage: () -> List<ImportFile>,
)

/**
 * What a [LibraryAlbumKind.FOLDER] gallery promises about the event album (decision record
 * `changes/archive/2026-09-30-android-event-album` D2, D3, D6, D8) — this list IS the specification of those
 * obligations. The core
 * builds on each: an empty album that resolved would never read as deleted; a move that minted a new id would let echo
 * suppression miss a received photo. An album's photos are read like any other photo of the default gallery — a
 * received one is kept out of the member's share by echo suppression, never by its folder (change
 * `close-share-back-gaps`).
 */
object FolderAlbumContract : Contract<FolderAlbumState, FolderAlbums>("FolderAlbum") {

    /** The album title a clause creates. Unique per clause, so a shared library cannot confuse two. */
    fun title(clauseId: String) = "snapsync-contract-$clauseId"

    private fun start(clauseId: String) = Instant.parse(PhotoLibrary.window(name, clauseId).start)

    override val clauses = clauses {

        clause(
            "A_FOLDER_LIBRARY_DECLARES_IT",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::albumKind).with(LibraryAlbumKind.FOLDER)
            },
        ) { subject ->
            assertEquals(
                LibraryAlbumKind.FOLDER,
                subject.gallery.albumKind,
                "a library whose albums are folders says so",
            )
        }

        clause(
            "TWO_ALBUMS_OF_ONE_TITLE_ARE_TWO_FOLDERS",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::createAlbum).returns()
            },
        ) { subject ->
            val title = title("TWO_ALBUMS_OF_ONE_TITLE_ARE_TWO_FOLDERS")
            val first = assertNotNull(subject.gallery.createAlbum(title))
            val second = assertNotNull(subject.gallery.createAlbum(title))
            assertNotEquals(first, second, "two events of one name never share a folder, even while the first is empty")
        }

        clause(
            "AN_EMPTY_ALBUM_RESOLVES_ONCE_A_PHOTO_LANDS",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::albumsById).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Ok::class)
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { subject ->
            val clauseId = "AN_EMPTY_ALBUM_RESOLVES_ONCE_A_PHOTO_LANDS"
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            assertEquals(
                GalleryRead.Read(emptyList()),
                subject.gallery.albumsById(setOf(album)),
                "an empty folder is no album, so the core cannot read a fresh one as present",
            )
            assertEquals(
                WriteOutcome.Ok,
                subject.gallery.addToAlbum(album, subject.seeded),
                "an empty album takes photos",
            )
            val byId = assertIs<GalleryRead.Read<List<GalleryAlbum>>>(subject.gallery.albumsById(setOf(album)))
            assertEquals(listOf(album), byId.value.map { it.id })
            assertEquals(GalleryRead.Read(subject.seeded), subject.gallery.albumMembers(album, start(clauseId)))
        }

        clause(
            "A_MOVED_PHOTO_KEEPS_ITS_ID_AND_STAYS_IN_THE_GALLERY",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::assets).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Ok::class)
                    answers(GalleryReader::assetsById).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { subject ->
            val clauseId = "A_MOVED_PHOTO_KEEPS_ITS_ID_AND_STAYS_IN_THE_GALLERY"
            val filter = PhotoLibrary.filter(name, clauseId)
            val before = assertIs<GalleryRead.Read<List<GalleryAsset>>>(subject.gallery.assets(filter))
            assertEquals(subject.seeded, before.value.mapTo(mutableSetOf()) { it.id } intersect subject.seeded)
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            val missing = absentAssetId(clauseId)
            assertEquals(
                WriteOutcome.Ok,
                subject.gallery.addToAlbum(album, subject.seeded + missing),
                "a missing id is skipped",
            )
            assertEquals(WriteOutcome.Ok, subject.gallery.addToAlbum(album, subject.seeded), "a second move is a no-op")
            val byId = assertIs<GalleryRead.Read<List<GalleryAsset>>>(subject.gallery.assetsById(subject.seeded))
            assertEquals(subject.seeded, byId.value.mapTo(mutableSetOf()) { it.id }, "each photo keeps its id")
            assertEquals(SEED_COUNT, byId.value.size, "each photo is in the library once")
            val after = assertIs<GalleryRead.Read<List<GalleryAsset>>>(subject.gallery.assets(filter))
            assertEquals(
                subject.seeded,
                after.value.mapTo(mutableSetOf()) { it.id } intersect subject.seeded,
                "an event album is a folder of the default gallery like any other: its photos are read where they are",
            )
        }

        clause(
            "AN_IMPORT_INTO_AN_ALBUM_LANDS_THERE",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::assetsById).withGenericLeaf(GalleryRead.Read::class)
                }
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "AN_IMPORT_INTO_AN_ALBUM_LANDS_THERE"
            val album = assertNotNull(subject.gallery.createAlbum(title(clauseId)))
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.gallery.import(
                GalleryImportContract.request(clauseId, subject.stage(), window.seedDate, album),
            )
            val id = assertIs<ImportOutcome.Imported>(result, "an ordinary photo imports").created
            val members = assertIs<GalleryRead.Read<Set<PlatformAssetId>>>(subject.gallery.albumMembers(album, null))
            assertEquals(setOf(id), members.value, "the received photo is in the album the moment it exists")
            val byId = assertIs<GalleryRead.Read<List<GalleryAsset>>>(subject.gallery.assetsById(setOf(id)))
            assertEquals(1, byId.value.size, "and in the library once")
        }

        clause(
            "A_MEMBERS_OWN_FOLDER_TAKES_NO_PHOTO",
            FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Failed::class)
                    answers(GalleryReader::assets).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { subject ->
            val clauseId = "A_MEMBERS_OWN_FOLDER_TAKES_NO_PHOTO"
            assertIs<WriteOutcome.Failed>(
                subject.gallery.addToAlbum(subject.ownFolder, subject.seeded),
                "only an event album's folder takes a photo; ${subject.ownFolder} is the member's",
            )
            val filter = PhotoLibrary.filter(name, clauseId)
            val after = assertIs<GalleryRead.Read<List<GalleryAsset>>>(subject.gallery.assets(filter))
            assertEquals(
                subject.seeded,
                after.value.mapTo(mutableSetOf()) { it.id } intersect subject.seeded,
                "nothing moved",
            )
        }
    }
}

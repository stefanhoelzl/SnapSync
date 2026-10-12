package app.snapsync.contracts

import app.snapsync.model.WriteOutcome
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The states a [GalleryReader]'s library can be found in, as far as a clause cares. */
enum class GalleryReaderState {
    /** The process holds no photo grant (undetermined or denied): there is no library to read. */
    NO_GRANT,

    /** No grant, and the process never asked — on a platform that keeps its own record of having asked. */
    NEVER_ASKED,

    /** No grant, and the process asked and was refused — on a platform that keeps its own record of having asked. */
    REFUSED,

    /** A full grant, and [SEED_COUNT] assets seeded in the clause's window. */
    GRANTED_SEEDED,

    /** A full grant, and nothing seeded in the clause's window. */
    GRANTED_EMPTY_WINDOW,

    /**
     * [GRANTED_SEEDED] on a platform whose albums are collections a photo is added to without moving (iOS). An Android
     * album is the folder a photo lives in, so adding one of the member's photos is not the app's to do: its album
     * clauses are `FolderAlbumContract`'s.
     */
    GRANTED_SEEDED_COLLECTION_ALBUMS,

    /**
     * A full grant, and [SEED_COUNT] assets seeded in the clause's window inside a folder of the default gallery named
     * for the clause — on a platform whose albums are folders (Android: a `DCIM` subfolder). iOS has no folders.
     */
    GRANTED_SEEDED_IN_A_FOLDER,

    /**
     * A full grant, and [SEED_COUNT] assets seeded in the clause's window OUTSIDE the member's default gallery — on a
     * platform whose default gallery is narrower than its library (Android: outside `DCIM`). On iOS the library IS it.
     */
    GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY,

    /** [GRANTED_SEEDED], and a place the clause may have the library export a resource to ([SeededLibrary.files]). */
    GRANTED_SEEDED_EXPORTING,

    /** A library that refuses this process's writes — iOS, to a process it has refused photo access. */
    REFUSING_WRITES,
}

/**
 * What every [GalleryReader] promises (`docs/architecture.md` — this list IS the specification of the port's
 * obligations). The port is thin: these clauses say what the platform shows, never what the core makes of it —
 * whether a walk is authoritative, which grant may answer a presence question and which albums are denied are the
 * services' (`GalleryDiscovery`, `GalleryAssetPresence`, `GalleryAlbums`), unit-tested over the honest fake.
 *
 * Two answers carry everything downstream:
 * - **`NotReadable` is never `Read(empty)`.** A counted zero settles the status screen and an empty
 *   authoritative walk deletes every in-window row; the collapse of the two shipped as `SNAPSYNC-14`/`16`.
 * - **A read by filter never omits an asset in its range.** The filter is a hint the library may over-return by, and
 *   what comes back is also a walk's presence set: an in-range asset it omits has its rows deleted as departed.
 *
 * Album titles derive from the clause id, so no clause meets another's album in a shared library.
 */
object GalleryReaderContract : Contract<GalleryReaderState, SeededLibrary<GalleryReader>>("GalleryReader") {

    private fun filter(clauseId: String) = PhotoLibrary.filter(name, clauseId)

    private fun window(clauseId: String) = PhotoLibrary.window(name, clauseId)

    /** The album title a clause creates. Unique per clause, so a shared library cannot confuse two. */
    fun title(clauseId: String) = "snapsync-contract-$clauseId"

    /** An album identifier no library holds. */
    private fun absentAlbumId(clauseId: String) =
        PlatformAlbumId("00000000-0000-4000-8000-${clauseId.length.toString().padStart(12, '0')}/L0/040")

    override val clauses = clauses {

        clause(
            "NO_GRANT_READS_NOTHING",
            GalleryReaderState.NO_GRANT,
            covers = cells {
                oneOf {
                    on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.NOT_DETERMINED)
                    on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.DENIED)
                }
                on<GalleryReader> {
                    answers(GalleryReader::assets).with(GalleryRead.NotReadable::class)
                    answers(GalleryReader::assetsById).with(GalleryRead.NotReadable::class)
                    answers(GalleryReader::resources).with(GalleryRead.NotReadable::class)
                    answers(GalleryReader::albums).with(GalleryRead.NotReadable::class)
                    answers(GalleryReader::albumsById).with(GalleryRead.NotReadable::class)
                    answers(GalleryReader::albumMembers).with(GalleryRead.NotReadable::class)
                }
            },
        ) { seeded ->
            val gallery = seeded.port
            val clauseId = "NO_GRANT_READS_NOTHING"
            val access = gallery.access()
            assertTrue(
                access == LibraryGrant.NOT_DETERMINED || access == LibraryGrant.DENIED,
                "a process holding no grant reads undetermined or denied, got $access",
            )
            val absent = setOf(absentAssetId(clauseId))
            assertEquals(GalleryRead.NotReadable, gallery.assets(filter(clauseId)), "no library to count is not a zero")
            assertEquals(GalleryRead.NotReadable, gallery.assetsById(absent))
            assertEquals(GalleryRead.NotReadable, gallery.resources(absent))
            assertEquals(GalleryRead.NotReadable, gallery.albums(), "an album fetch while undetermined raises a dialog")
            assertEquals(GalleryRead.NotReadable, gallery.albumsById(setOf(absentAlbumId(clauseId))))
            assertEquals(GalleryRead.NotReadable, gallery.albumMembers(absentAlbumId(clauseId), createdFrom = null))
        }

        clause(
            "NEVER_ASKED_READS_UNDETERMINED",
            GalleryReaderState.NEVER_ASKED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.NOT_DETERMINED)
            },
        ) { seeded ->
            assertEquals(LibraryGrant.NOT_DETERMINED, seeded.port.access(), "never asked is still undecided")
        }

        clause(
            "REFUSED_READS_DENIED",
            GalleryReaderState.REFUSED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.DENIED)
            },
        ) { seeded ->
            assertEquals(LibraryGrant.DENIED, seeded.port.access(), "asked and refused is denied")
        }

        clause(
            "EXPORTING_A_RESOURCE_WRITES_ITS_BYTES_AND_A_FOREIGN_ONE_FAILS",
            GalleryReaderState.GRANTED_SEEDED_EXPORTING,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::resources).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::export).with(WriteOutcome.Ok::class)
                    answers(GalleryReader::export).with(WriteOutcome.Failed::class)
                }
            },
        ) { seeded ->
            val files =
                assertNotNull(seeded.files, "a binding that reaches this state gives the clause a place to write")
            val read = assertIs<GalleryRead.Read<List<GalleryAssetResources>>>(seeded.port.resources(seeded.ids))
            val resource = read.value.first().resources.first { it.kind == ResourceKind.PHOTO }
            val to = files.pathOf("exported.bin")
            assertEquals(WriteOutcome.Ok, seeded.port.export(resource.handle, to), "the library's own resource exports")
            assertTrue((files.read(to)?.size ?: 0) > 0, "and its bytes are at the path it was given")
            val foreign = files.pathOf("foreign.bin")
            assertIs<WriteOutcome.Failed>(
                seeded.port.export(object : GalleryResourceHandle {}, foreign),
                "a handle the library did not hand out cannot be produced",
            )
            assertNull(files.read(foreign), "and nothing is left where it was to go")
        }

        clause(
            "REFUSING_WRITES_AN_ALBUM_NOT_CREATED_IS_NO_ID",
            GalleryReaderState.REFUSING_WRITES,
            covers = cells { on<GalleryReader>().answers(GalleryReader::createAlbum).with(null) },
        ) { seeded ->
            assertNull(
                seeded.port.createAlbum(title("REFUSING_WRITES_AN_ALBUM_NOT_CREATED_IS_NO_ID")),
                "an album the library refused to create is no id: nothing could be filed into it",
            )
        }

        clause(
            "A_COLLECTION_LIBRARY_DECLARES_IT",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::albumKind).with(LibraryAlbumKind.COLLECTION)
            },
        ) { seeded ->
            // What the core places in an album follows the kind: a collection library also takes the member's own photos.
            assertEquals(
                LibraryAlbumKind.COLLECTION,
                seeded.port.albumKind,
                "a library whose albums are collections says so",
            )
        }

        clause(
            "GRANTED_READS_GRANTED",
            GalleryReaderState.GRANTED_EMPTY_WINDOW,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.GRANTED)
            },
        ) { seeded ->
            assertEquals(LibraryGrant.GRANTED, seeded.port.access())
        }

        clause(
            "ASSETS_RETURN_EVERY_SEEDED_ASSET",
            GalleryReaderState.GRANTED_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::assets).withGenericLeaf(GalleryRead.Read::class)
            },
        ) { seeded ->
            val read = assertIs<GalleryRead.Read<*>>(seeded.port.assets(filter("ASSETS_RETURN_EVERY_SEEDED_ASSET")))
            val returned = seeded.port.assetIdsOf(read)
            assertEquals(SEED_COUNT, seeded.ids.size, "the binding seeded what the state promises")
            assertTrue(
                returned.containsAll(seeded.ids),
                "a read may return a superset but never a subset: missing ${seeded.ids - returned}",
            )
        }

        clause(
            "AN_EMPTY_WINDOW_IS_A_COUNTED_ZERO",
            GalleryReaderState.GRANTED_EMPTY_WINDOW,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::assets).withGenericLeaf(GalleryRead.Read::class)
            },
        ) { seeded ->
            val clauseId = "AN_EMPTY_WINDOW_IS_A_COUNTED_ZERO"
            val read = assertIs<GalleryRead.Read<List<GalleryAsset>>>(
                seeded.port.assets(filter(clauseId)),
                "a readable library with nothing in the window is a counted zero, never 'no answer'",
            )
            val inWindow = read.value.filter { asset -> asset.created?.let { it.toString() in window(clauseId) } == true }
            assertTrue(inWindow.isEmpty(), "nothing was seeded in this window, yet ${inWindow.size} came back")
        }

        clause(
            "ASSETS_BY_ID_RETURN_EXACTLY_THE_ONES_STILL_THERE",
            GalleryReaderState.GRANTED_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::assetsById).withGenericLeaf(GalleryRead.Read::class)
            },
        ) { seeded ->
            val asked = seeded.ids + absentAssetId("ASSETS_BY_ID_RETURN_EXACTLY_THE_ONES_STILL_THERE")
            val read = assertIs<GalleryRead.Read<List<GalleryAsset>>>(seeded.port.assetsById(asked))
            assertEquals(
                seeded.ids,
                read.value.mapTo(mutableSetOf()) { it.id },
                "a missing id is simply not returned",
            )
        }

        clause(
            "RESOURCES_BY_ID_CARRY_EACH_ORIGINAL",
            GalleryReaderState.GRANTED_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::resources).withGenericLeaf(GalleryRead.Read::class)
            },
        ) { seeded ->
            val asked = seeded.ids + absentAssetId("RESOURCES_BY_ID_CARRY_EACH_ORIGINAL")
            val read = assertIs<GalleryRead.Read<List<GalleryAssetResources>>>(seeded.port.resources(asked))
            assertEquals(
                seeded.ids,
                read.value.mapTo(mutableSetOf()) { it.asset.id },
                "a missing id is not returned",
            )
            for (asset in read.value) {
                assertTrue(
                    asset.resources.any { it.kind == ResourceKind.PHOTO },
                    "a seeded photo carries its still as an original: ${asset.asset.id}",
                )
            }
        }

        clause(
            "READING_NOTHING_BY_ID_READS_NOTHING",
            GalleryReaderState.GRANTED_SEEDED,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::assetsById).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::resources).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            assertEquals(GalleryRead.Read(emptyList()), seeded.port.assetsById(emptySet()))
            assertEquals(GalleryRead.Read(emptyList()), seeded.port.resources(emptySet()))
        }

        clause(
            "A_CREATED_ALBUM_RESOLVES_AND_IS_LISTED",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::albumsById).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::albums).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val title = title("A_CREATED_ALBUM_RESOLVES_AND_IS_LISTED")
            val id = assertNotNull(seeded.port.createAlbum(title))
            val byId = assertIs<GalleryRead.Read<List<GalleryAlbum>>>(seeded.port.albumsById(setOf(id)))
            assertEquals(listOf(id), byId.value.map { it.id })
            val listed = assertIs<GalleryRead.Read<List<GalleryAlbum>>>(seeded.port.albums())
            assertTrue(listed.value.any { it.id == id && it.title == title }, "the album is listed under its title")
        }

        clause(
            "AN_UNKNOWN_ALBUM_DOES_NOT_RESOLVE",
            GalleryReaderState.GRANTED_SEEDED,
            covers = cells {
                on<GalleryReader>().answers(GalleryReader::albumsById).withGenericLeaf(GalleryRead.Read::class)
            },
        ) { seeded ->
            assertEquals(
                GalleryRead.Read(emptyList()),
                seeded.port.albumsById(setOf(absentAlbumId("AN_UNKNOWN_ALBUM_DOES_NOT_RESOLVE"))),
            )
        }

        clause(
            "ADDED_ASSETS_ARE_MEMBERS",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Ok::class)
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val clauseId = "ADDED_ASSETS_ARE_MEMBERS"
            val album = assertNotNull(seeded.port.createAlbum(title(clauseId)))
            assertEquals(WriteOutcome.Ok, seeded.port.addToAlbum(album, seeded.ids))
            assertEquals(
                GalleryRead.Read(seeded.ids),
                seeded.port.albumMembers(album, Instant.parse(window(clauseId).start)),
                "every asset added to an album is a member of it, in the id form it was handed in",
            )
        }

        clause(
            "MEMBERS_CAPTURED_BEFORE_SINCE_ARE_NOT_RETURNED",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val clauseId = "MEMBERS_CAPTURED_BEFORE_SINCE_ARE_NOT_RETURNED"
            val album = assertNotNull(seeded.port.createAlbum(title(clauseId)))
            seeded.port.addToAlbum(album, seeded.ids)
            assertEquals(
                GalleryRead.Read(emptySet()),
                seeded.port.albumMembers(album, Instant.parse(window(clauseId).end)),
                "the member read is bounded by the membership's floor, so its cost follows the event, not the library",
            )
        }

        clause(
            "ADD_TO_A_MISSING_ALBUM_FAILS_AND_CREATES_NOTHING",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Failed::class)
                    answers(GalleryReader::albumsById).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val album = absentAlbumId("ADD_TO_A_MISSING_ALBUM_FAILS_AND_CREATES_NOTHING")
            assertIs<WriteOutcome.Failed>(seeded.port.addToAlbum(album, seeded.ids))
            assertEquals(GalleryRead.Read(emptyList()), seeded.port.albumsById(setOf(album)), "adding never creates")
        }

        clause(
            "ADD_OF_A_MISSING_ASSET_IS_SKIPPED",
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::createAlbum).returns()
                    answers(GalleryReader::addToAlbum).with(WriteOutcome.Ok::class)
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val clauseId = "ADD_OF_A_MISSING_ASSET_IS_SKIPPED"
            val album = assertNotNull(seeded.port.createAlbum(title(clauseId)))
            assertEquals(WriteOutcome.Ok, seeded.port.addToAlbum(album, seeded.ids + absentAssetId(clauseId)))
            assertEquals(
                GalleryRead.Read(seeded.ids),
                seeded.port.albumMembers(album, Instant.parse(window(clauseId).start)),
                "an asset the library does not hold is skipped, and the ones it holds are still added",
            )
        }

        clause(
            "A_FOLDER_IS_A_LISTED_ALBUM_OF_ITS_ASSETS",
            GalleryReaderState.GRANTED_SEEDED_IN_A_FOLDER,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::albums).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::albumsById).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::albumMembers).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val clauseId = "A_FOLDER_IS_A_LISTED_ALBUM_OF_ITS_ASSETS"
            val listed = assertIs<GalleryRead.Read<List<GalleryAlbum>>>(seeded.port.albums())
            val album = assertNotNull(
                listed.value.singleOrNull { it.title == title(clauseId) },
                "the folder is listed once, under its own name: ${listed.value.map { it.title }}",
            )
            assertEquals(GalleryRead.Read(listOf(album)), seeded.port.albumsById(setOf(album.id)))
            assertEquals(
                GalleryRead.Read(seeded.ids),
                seeded.port.albumMembers(album.id, Instant.parse(window(clauseId).start)),
                "a folder's album holds exactly the assets in the folder — what the album denylist excludes",
            )
        }

        clause(
            "ASSETS_OUTSIDE_THE_DEFAULT_GALLERY_ARE_NOT_READ",
            GalleryReaderState.GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY,
            covers = cells {
                on<GalleryReader> {
                    answers(GalleryReader::assets).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::assetsById).withGenericLeaf(GalleryRead.Read::class)
                    answers(GalleryReader::resources).withGenericLeaf(GalleryRead.Read::class)
                }
            },
        ) { seeded ->
            val clauseId = "ASSETS_OUTSIDE_THE_DEFAULT_GALLERY_ARE_NOT_READ"
            assertEquals(SEED_COUNT, seeded.ids.size, "the binding seeded what the state promises")
            val read = assertIs<GalleryRead.Read<List<GalleryAsset>>>(seeded.port.assets(filter(clauseId)))
            val leaked = read.value.mapTo(mutableSetOf()) { it.id } intersect seeded.ids
            assertTrue(leaked.isEmpty(), "an asset outside the default gallery is never a candidate to share: $leaked")
            assertEquals(GalleryRead.Read(emptyList()), seeded.port.assetsById(seeded.ids))
            assertEquals(GalleryRead.Read(emptyList()), seeded.port.resources(seeded.ids))
        }
    }

    private fun GalleryReader.assetIdsOf(read: GalleryRead.Read<*>): Set<PlatformAssetId> =
        (read.value as List<*>).mapTo(mutableSetOf()) { (it as GalleryAsset).id }
}

package app.snapsync.contracts

import app.snapsync.ports.GalleryImport
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What a binding has staged for a clause's import. */
enum class LivePhotoImportState {
    /** A full grant, and one Google motion photo staged, as an Android member shares it. */
    GRANTED_MOTION_PHOTO_STAGED,

    /** A full grant, and one JPEG whose motion-photo XMP points at a trailer that is no video. */
    GRANTED_BROKEN_MOTION_PHOTO_STAGED,
}

/** What a clause observes of an imported asset, beyond [ImportedLibrary]: what kind of photo the library made of it. */
interface LivePhotoLibrary : ImportedLibrary {
    /** Whether the asset with [id] is a Live Photo, or `null` if none exists. */
    suspend fun isLivePhoto(id: PlatformAssetId): Boolean?

    /** The kinds of the asset's resources as the library names them (`photo`, `pairedVideo`), in its order. */
    suspend fun resourceKinds(id: PlatformAssetId): List<String>
}

/** The gallery's import, a way to stage the state's photo, and the handle over its library — as [StagedImport]. */
class StagedLiveImport(
    val importer: GalleryImport,
    val stage: () -> List<ImportFile>,
    val library: LivePhotoLibrary,
)

/**
 * **A received motion photo becomes a Live Photo** (an Android motion photo reaches an iPhone as a Live Photo;
 * decision record `changes/archive/2026-10-01-live-motion-unification` D4–D6). The iPhone's alone: Android
 * keeps a motion photo as the file it is, so only an iPhone host binds this.
 *
 * What it pins is what no unit test can: that the library ACCEPTS the pair the importer builds — a still carrying the
 * content identifier and a QuickTime movie carrying it and the key-frame marker — as ONE Live Photo, and that a file
 * that only looks like a motion photo still arrives, once, as its still.
 */
object LivePhotoImportContract : Contract<LivePhotoImportState, StagedLiveImport>("LivePhotoImport") {

    override val clauses = clauses {

        clause(
            "A_MOTION_PHOTO_IMPORTS_AS_ONE_LIVE_PHOTO",
            LivePhotoImportState.GRANTED_MOTION_PHOTO_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "A_MOTION_PHOTO_IMPORTS_AS_ONE_LIVE_PHOTO"
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.importer.import(
                GalleryImportContract.request(clauseId, subject.stage(), window.seedDate),
            )
            val id = assertIs<ImportOutcome.Imported>(result, "a motion photo imports").created
            assertEquals(true, subject.library.isLivePhoto(id), "the library made a Live Photo of it")
            assertEquals(
                listOf("photo", "pairedVideo"),
                subject.library.resourceKinds(id),
                "one asset: its still and its video",
            )
            assertEquals(window.seedDate, subject.library.captureDate(id), "at its capture date")
            assertEquals(MarkerState.CONFIRMED, subject.library.marker(GalleryImportContract.correlation(clauseId)))
        }

        clause(
            "A_FILE_THAT_ONLY_LOOKS_LIKE_A_MOTION_PHOTO_IMPORTS_AS_ITS_STILL",
            LivePhotoImportState.GRANTED_BROKEN_MOTION_PHOTO_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "A_FILE_THAT_ONLY_LOOKS_LIKE_A_MOTION_PHOTO_IMPORTS_AS_ITS_STILL"
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.importer.import(
                GalleryImportContract.request(clauseId, subject.stage(), window.seedDate),
            )
            val id = assertIs<ImportOutcome.Imported>(result, "it still arrives").created
            assertEquals(false, subject.library.isLivePhoto(id), "as a plain photo")
            assertEquals(listOf("photo"), subject.library.resourceKinds(id), "the file as it was staged, once")
            assertEquals(MarkerState.CONFIRMED, subject.library.marker(GalleryImportContract.correlation(clauseId)))
        }
    }
}

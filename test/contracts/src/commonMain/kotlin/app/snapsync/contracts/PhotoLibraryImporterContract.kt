package app.snapsync.contracts

import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.model.StagedResource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What a binding has staged for a clause's import, as far as a clause cares. */
enum class PhotoLibraryImporterState {
    /** A full grant, and one ordinary photo staged. */
    GRANTED_VALID_STAGED,

    /** A full grant, and one file staged under an image type that no library can decode. */
    GRANTED_INVALID_STAGED,
}

/**
 * Where an import's marker stands for one ref, as the store the importer writes through records it — the
 * **state reached**, not the calls that reached it.
 */
enum class MarkerState { NONE, RECORDED, CONFIRMED, CLEARED }

/**
 * What a clause observes of the library an importer writes into (`docs/architecture.md`: a port that
 * declares no reads of its own is observed through a handle each binding implements over the system it built).
 * Outcomes only.
 */
interface ImportedLibrary {
    /** The capture date of the asset with normalized [id], as an ISO-8601 instant, or `null` if none exists. */
    suspend fun captureDate(id: String): String?

    /** Where the marker for [ref] stands in the store the importer records through. */
    fun marker(ref: AssetRef): MarkerState
}

/**
 * The importer as a clause receives it: the port, a way to stage the state's resources, and the handle over its
 * library. [stage] stages a FRESH copy on every call, because a library takes a resource's file when it ingests
 * it, so a second import needs files of its own.
 */
class StagedImport(
    val importer: PhotoLibraryImporter,
    val stage: () -> List<StagedResource>,
    val library: ImportedLibrary,
)

/**
 * What every [PhotoLibraryImporter] promises (`docs/architecture.md` — this list IS the specification of
 * the port's obligations).
 *
 * The import is where a foreign photo enters this device's library, and every failure shape matters: a
 * duplicate is a photo shown twice to every member, and a failure that consumed its file but is retried is a
 * retry against a file that no longer exists, forever.
 */
object PhotoLibraryImporterContract : Contract<PhotoLibraryImporterState, StagedImport>("PhotoLibraryImporter") {

    /** The ref a clause imports under. Deterministic, and distinct per clause. */
    fun ref(clauseId: String) = AssetRef(sourceDeviceId = "contract-device", sourceAssetId = clauseId)

    override val clauses = clauses {

        clause("IMPORT_LANDS_AT_ITS_CAPTURE_DATE", PhotoLibraryImporterState.GRANTED_VALID_STAGED) { subject ->
            val clauseId = "IMPORT_LANDS_AT_ITS_CAPTURE_DATE"
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.importer.import(ref(clauseId), subject.stage(), window.seedDate, album = null)
            val id = assertIs<ImportResult.Imported>(result, "an ordinary photo imports").createdLocalId
            assertEquals(
                window.seedDate,
                subject.library.captureDate(id),
                "an import sorts by its original capture date, not by when it arrived",
            )
            assertEquals(MarkerState.CONFIRMED, subject.library.marker(ref(clauseId)))
        }

        clause("A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET", PhotoLibraryImporterState.GRANTED_VALID_STAGED) { subject ->
            val clauseId = "A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val first = assertIs<ImportResult.Imported>(subject.importer.import(ref(clauseId), subject.stage(), date, album = null))
            val second = assertIs<ImportResult.Imported>(subject.importer.import(ref(clauseId), subject.stage(), date, album = null))
            assertNotEquals(
                first.createdLocalId,
                second.createdLocalId,
                "every creation is a new asset with a new identifier, which is why the caller must never repeat one",
            )
            assertEquals(date, subject.library.captureDate(first.createdLocalId))
            assertEquals(date, subject.library.captureDate(second.createdLocalId))
        }

        clause("AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED", PhotoLibraryImporterState.GRANTED_INVALID_STAGED) { subject ->
            val clauseId = "AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val failed = assertIs<ImportResult.Failed>(subject.importer.import(ref(clauseId), subject.stage(), date, album = null))
            assertTrue(
                failed.consumedResources,
                "the library takes a file when it ingests it, before validating it; retrying reads a file that is gone",
            )
            assertTrue(
                subject.library.marker(ref(clauseId)) != MarkerState.CONFIRMED,
                "a failed import never confirms its marker",
            )
        }
    }
}

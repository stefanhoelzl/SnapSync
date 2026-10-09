package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.StagedResource
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImport
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What a binding has staged for a clause's import, as far as a clause cares. */
enum class GalleryImportState {
    /** A full grant, and one ordinary photo staged. */
    GRANTED_VALID_STAGED,

    /** A full grant, and one file staged under an image type that no library can decode. */
    GRANTED_INVALID_STAGED,
}

/**
 * Where an import's marker stands for one ref, as the import handlers the binding registered record it — the
 * **state reached**, not the calls that reached it: `onImportPlaceholder` records, `onImportSettled` confirms an
 * `Imported` and clears a `Failed` placeholder.
 */
enum class MarkerState { NONE, RECORDED, CONFIRMED, CLEARED }

/**
 * The import handlers a binding registers on the gallery it built ([handlers]), and what the gallery delivered to them
 * per ref: the asset a placeholder named, and the outcome a settle carried. An event port's delivery IS its adapter's
 * answer, so this is the outcome a clause reads — the last delivery wins, as the port promises a repeated change block's
 * does.
 */
class ImportDeliveries {
    private val placeholders = mutableMapOf<AssetRef, AssetId>()
    private val settles = mutableMapOf<AssetRef, ImportResult>()

    val handlers = GalleryHandlers(
        onChanged = {},
        onImportPlaceholder = { ref, id -> placeholders[ref] = id },
        onImportSettled = { ref, result -> settles[ref] = result },
    )

    /** The asset the last placeholder for [ref] named, or `null` if none was delivered. */
    fun placeholder(ref: AssetRef): AssetId? = placeholders[ref]

    /** The outcome the settle for [ref] carried, or `null` if none was delivered. */
    fun settled(ref: AssetRef): ImportResult? = settles[ref]

    /** Where [ref]'s marker stands after these deliveries. */
    fun marker(ref: AssetRef): MarkerState = when (settled(ref)) {
        is ImportResult.Imported -> MarkerState.CONFIRMED
        is ImportResult.Failed -> if (placeholder(ref) != null) MarkerState.CLEARED else MarkerState.NONE
        null -> if (placeholder(ref) != null) MarkerState.RECORDED else MarkerState.NONE
    }
}

/**
 * What a clause observes of the library an importer writes into (`docs/architecture.md`: a port that
 * declares no reads of its own is observed through a handle each binding implements over the system it built).
 * Outcomes only.
 */
interface ImportedLibrary {
    /** The capture date of the asset with [id], as an ISO-8601 instant, or `null` if none exists. */
    suspend fun captureDate(id: AssetId): String?

    /** What the gallery delivered to the import handlers the binding registered. */
    val deliveries: ImportDeliveries

    /** Where the marker for [ref] stands, as the registered import handlers recorded it. */
    fun marker(ref: AssetRef): MarkerState = deliveries.marker(ref)

    /**
     * The filename the library reports for the PRIMARY resource of the asset with [id] — what a later install reads
     * back to recognise the photo as received — or `null` if none exists.
     */
    suspend fun primaryFilename(id: AssetId): String?
}

/**
 * The gallery's import as a clause receives it — already listened to by handlers that record the markers — a way to
 * stage the state's resources, and the handle over its library. [stage] stages a FRESH copy on every call, because a
 * library takes a resource's file when it ingests it, so a second import needs files of its own.
 */
class StagedImport(
    val importer: GalleryImport,
    val stage: () -> List<StagedResource>,
    val library: ImportedLibrary,
)

/**
 * What every [GalleryImport] promises (`docs/architecture.md` — this list IS the specification of
 * the port's obligations).
 *
 * The import is where a foreign photo enters this device's library, and every failure shape matters: a
 * duplicate is a photo shown twice to every member, and a failure that consumed its file but is retried is a
 * retry against a file that no longer exists, forever.
 */
object GalleryImportContract : Contract<GalleryImportState, StagedImport>("GalleryImport") {

    /** The ref a clause imports under. Deterministic, and distinct per clause. */
    fun ref(clauseId: String) = AssetRef(sourceDeviceId = "contract-device", sourceAssetId = AssetId(clauseId))

    override val clauses = clauses {

        clause(
            "IMPORT_LANDS_AT_ITS_CAPTURE_DATE",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportResult.Imported::class)
                on<Gallery> {
                    calls(GalleryHandlers::onImportPlaceholder, AssetRef::class, AssetId::class)
                    calls(GalleryHandlers::onImportSettled, AssetRef::class, ImportResult.Imported::class)
                }
            },
        ) { subject ->
            val clauseId = "IMPORT_LANDS_AT_ITS_CAPTURE_DATE"
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.importer.import(
                ImportRequest(ref(clauseId), subject.stage(), window.seedDate, album = null),
            )
            val id = assertIs<ImportResult.Imported>(result, "an ordinary photo imports").createdLocalId
            assertEquals(
                window.seedDate,
                subject.library.captureDate(id),
                "an import sorts by its original capture date, not by when it arrived",
            )
            val delivered = subject.library.deliveries
            assertEquals(id, delivered.placeholder(ref(clauseId)), "the placeholder names the asset the import created")
            assertEquals(result, delivered.settled(ref(clauseId)), "the settle carries the answer the import returns")
            assertEquals(MarkerState.CONFIRMED, subject.library.marker(ref(clauseId)))
        }

        clause(
            "AN_IMPORT_CARRIES_ITS_REFS_MARK",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportResult.Imported::class)
            },
        ) { subject ->
            val clauseId = "AN_IMPORT_CARRIES_ITS_REFS_MARK"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val result = subject.importer.import(ImportRequest(ref(clauseId), subject.stage(), date, album = null))
            val id = assertIs<ImportResult.Imported>(result, "an ordinary photo imports").createdLocalId
            val filename = subject.library.primaryFilename(id)
            assertEquals(
                ReceivedPhotoName.token(ref(clauseId)),
                filename?.let(ReceivedPhotoName::tokenOf),
                "the library keeps the mark on the name it reports ('$filename'): it is how a reinstalled app knows the " +
                    "photo was received",
            )
        }

        clause(
            "A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportResult.Imported::class)
            },
        ) { subject ->
            val clauseId = "A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val first =
                assertIs<ImportResult.Imported>(
                    subject.importer.import(ImportRequest(ref(clauseId), subject.stage(), date, album = null)),
                )
            val second =
                assertIs<ImportResult.Imported>(
                    subject.importer.import(ImportRequest(ref(clauseId), subject.stage(), date, album = null)),
                )
            assertNotEquals(
                first.createdLocalId,
                second.createdLocalId,
                "every creation is a new asset with a new identifier, which is why the caller must never repeat one",
            )
            assertEquals(date, subject.library.captureDate(first.createdLocalId))
            assertEquals(date, subject.library.captureDate(second.createdLocalId))
        }

        clause(
            "AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED",
            GalleryImportState.GRANTED_INVALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportResult.Failed::class)
                on<Gallery>().calls(GalleryHandlers::onImportSettled, AssetRef::class, ImportResult.Failed::class)
            },
        ) { subject ->
            val clauseId = "AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val failed =
                assertIs<ImportResult.Failed>(
                    subject.importer.import(ImportRequest(ref(clauseId), subject.stage(), date, album = null)),
                )
            assertTrue(
                failed.consumedResources,
                "the library takes a file when it ingests it, before validating it; retrying reads a file that is gone",
            )
            assertEquals(
                failed,
                subject.library.deliveries.settled(ref(clauseId)),
                "a failure is settled too, from the platform's completion, before the import returns",
            )
            assertTrue(
                subject.library.marker(ref(clauseId)) != MarkerState.CONFIRMED,
                "a failed import never confirms its marker",
            )
            assertTrue(
                subject.library.marker(ref(clauseId)) != MarkerState.RECORDED,
                "a failed import settles its marker before it returns: a recorded one would be skipped as created",
            )
        }
    }
}

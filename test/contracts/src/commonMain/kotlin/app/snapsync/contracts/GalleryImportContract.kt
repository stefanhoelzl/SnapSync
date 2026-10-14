@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.ImportPlaceholder
import app.snapsync.ports.ImportSettled
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What a binding has staged for a clause's import, as far as a clause cares. */
enum class GalleryImportState {
    /** A full grant, and one ordinary photo staged. */
    GRANTED_VALID_STAGED,

    /** A full grant, and one file staged under an image type that no library can decode. */
    GRANTED_INVALID_STAGED,

    /** [GRANTED_VALID_STAGED], and an album this process created in a library whose albums are collections ([StagedImport.album]). */
    GRANTED_VALID_STAGED_WITH_AN_ALBUM,

    /** [GRANTED_VALID_STAGED] in a library whose albums are collections, which no longer holds the album an import names. */
    GRANTED_VALID_STAGED_ALBUM_GONE,
}

/**
 * Where an import's marker stands for one ref, as the import handlers the binding registered record it — the
 * **state reached**, not the calls that reached it: `onImportPlaceholder` records, `onImportSettled` confirms an
 * `Imported` and clears a `Failed` placeholder.
 */
enum class MarkerState { NONE, RECORDED, CONFIRMED, CLEARED }

/**
 * The import handlers a binding registers on the gallery it built ([handlers]), and what the gallery delivered to them
 * per import's correlation: the asset a placeholder named, and the outcome a settle carried. An event port's delivery IS its adapter's
 * answer, so this is the outcome a clause reads — the last delivery wins, as the port promises a repeated change block's
 * does.
 */
class ImportDeliveries {
    private val placeholders = mutableMapOf<String, PlatformAssetId>()
    private val settles = mutableMapOf<String, ImportOutcome>()

    val handlers = GalleryHandlers(
        onImportPlaceholder = inboundOf { placeholders[it.correlation] = it.created },
        onImportSettled = inboundOf { settles[it.correlation] = it.outcome },
    )

    /** The asset the last placeholder for [correlation] named, or `null` if none was delivered. */
    fun placeholder(correlation: String): PlatformAssetId? = placeholders[correlation]

    /** The outcome the settle for [correlation] carried, or `null` if none was delivered. */
    fun settled(correlation: String): ImportOutcome? = settles[correlation]

    /** Where [correlation]'s marker stands after these deliveries. */
    fun marker(correlation: String): MarkerState = when (settled(correlation)) {
        is ImportOutcome.Imported -> MarkerState.CONFIRMED
        is ImportOutcome.Failed -> if (placeholder(correlation) != null) MarkerState.CLEARED else MarkerState.NONE
        null -> if (placeholder(correlation) != null) MarkerState.RECORDED else MarkerState.NONE
    }
}

/**
 * What a clause observes of the library an importer writes into (`docs/architecture.md`: a port that
 * declares no reads of its own is observed through a handle each binding implements over the system it built).
 * Outcomes only.
 */
interface ImportedLibrary {
    /** The capture date of the asset with [id], as an ISO-8601 instant, or `null` if none exists. */
    suspend fun captureDate(id: PlatformAssetId): String?

    /** What the gallery delivered to the import handlers the binding registered. */
    val deliveries: ImportDeliveries

    /** Where the marker for [correlation] stands, as the registered import handlers recorded it. */
    fun marker(correlation: String): MarkerState = deliveries.marker(correlation)

    /**
     * The filename the library reports for the still (or the video) of the asset with [id] — what a later install
     * reads back to recognise the photo as received — or `null` if none exists.
     */
    suspend fun primaryFilename(id: PlatformAssetId): String?
}

/**
 * The gallery's import as a clause receives it — already listened to by handlers that record the markers — a way to
 * stage the state's files, and the handle over its library. [stage] stages a FRESH copy on every call, because a
 * library takes a file when it ingests it, so a second import needs files of its own.
 */
class StagedImport(
    val importer: GalleryImport,
    val stage: () -> List<ImportFile>,
    val library: ImportedLibrary,
    /** The album the binding created for [GalleryImportState.GRANTED_VALID_STAGED_WITH_AN_ALBUM]; `null` in every other state. */
    val album: ImportAlbum? = null,
)

/** An album a binding created as this process creates one: its [id], and whether it [holds] an asset now. */
class ImportAlbum(val id: PlatformAlbumId, val holds: suspend (PlatformAssetId) -> Boolean)

/**
 * What every [GalleryImport] promises (`docs/architecture.md` — this list IS the specification of
 * the port's obligations).
 *
 * The import is where a foreign photo enters this device's library, and every failure shape matters: a
 * duplicate is a photo shown twice to every member, and a failure that consumed its file but is retried is a
 * retry against a file that no longer exists, forever.
 */
object GalleryImportContract : Contract<GalleryImportState, StagedImport>("GalleryImport") {

    /** The correlation a clause imports under. Deterministic, and distinct per clause. */
    fun correlation(clauseId: String) = "contract-device/$clauseId"

    /** The request a clause makes: [files] for [clauseId], captured at [date]. */
    fun request(clauseId: String, files: List<ImportFile>, date: String, album: PlatformAlbumId? = null) =
        GalleryImportRequest(correlation(clauseId), files, Instant.parse(date), album)

    override val clauses = clauses {

        clause(
            "IMPORT_LANDS_AT_ITS_CAPTURE_DATE",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
                on<Gallery> {
                    calls(GalleryHandlers::onImportPlaceholder, ImportPlaceholder::class)
                    calls(GalleryHandlers::onImportSettled, ImportSettled::class)
                }
            },
        ) { subject ->
            val clauseId = "IMPORT_LANDS_AT_ITS_CAPTURE_DATE"
            val window = PhotoLibrary.window(name, clauseId)
            val result = subject.importer.import(request(clauseId, subject.stage(), window.seedDate))
            val id = assertIs<ImportOutcome.Imported>(result, "an ordinary photo imports").created
            assertEquals(
                window.seedDate,
                subject.library.captureDate(id),
                "an import sorts by its original capture date, not by when it arrived",
            )
            val delivered = subject.library.deliveries
            assertEquals(
                id,
                delivered.placeholder(correlation(clauseId)),
                "the placeholder names the asset the import created",
            )
            assertEquals(
                result,
                delivered.settled(correlation(clauseId)),
                "the settle carries the answer the import returns",
            )
            assertEquals(MarkerState.CONFIRMED, subject.library.marker(correlation(clauseId)))
        }

        clause(
            "AN_IMPORT_KEEPS_THE_NAME_IT_WAS_GIVEN",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "AN_IMPORT_KEEPS_THE_NAME_IT_WAS_GIVEN"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val files = subject.stage()
            val result = subject.importer.import(request(clauseId, files, date))
            val id = assertIs<ImportOutcome.Imported>(result, "an ordinary photo imports").created
            assertEquals(
                files.first().name,
                subject.library.primaryFilename(id),
                "the library reports the name it was given: it is how a reinstalled app knows the photo was received",
            )
        }

        clause(
            "A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET",
            GalleryImportState.GRANTED_VALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "A_REPEAT_IMPORT_CREATES_A_SECOND_ASSET"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val first =
                assertIs<ImportOutcome.Imported>(
                    subject.importer.import(request(clauseId, subject.stage(), date)),
                )
            val second =
                assertIs<ImportOutcome.Imported>(
                    subject.importer.import(request(clauseId, subject.stage(), date)),
                )
            assertNotEquals(
                first.created,
                second.created,
                "every creation is a new asset with a new identifier, which is why the caller must never repeat one",
            )
            assertEquals(date, subject.library.captureDate(first.created))
            assertEquals(date, subject.library.captureDate(second.created))
        }

        clause(
            "AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED",
            GalleryImportState.GRANTED_INVALID_STAGED,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Failed::class)
                on<Gallery>().calls(GalleryHandlers::onImportSettled, ImportSettled::class)
            },
        ) { subject ->
            val clauseId = "AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val failed =
                assertIs<ImportOutcome.Failed>(
                    subject.importer.import(request(clauseId, subject.stage(), date)),
                )
            assertTrue(
                failed.consumedResources,
                "the library takes a file when it ingests it, before validating it; retrying reads a file that is gone",
            )
            assertEquals(
                failed,
                subject.library.deliveries.settled(correlation(clauseId)),
                "a failure is settled too, from the platform's completion, before the import returns",
            )
            assertTrue(
                subject.library.marker(correlation(clauseId)) != MarkerState.CONFIRMED,
                "a failed import never confirms its marker",
            )
            assertTrue(
                subject.library.marker(correlation(clauseId)) != MarkerState.RECORDED,
                "a failed import settles its marker before it returns: a recorded one would be skipped as created",
            )
        }

        clause(
            "AN_IMPORT_INTO_AN_ALBUM_FILES_IT_THERE",
            GalleryImportState.GRANTED_VALID_STAGED_WITH_AN_ALBUM,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "AN_IMPORT_INTO_AN_ALBUM_FILES_IT_THERE"
            val album = assertNotNull(subject.album, "the binding created the album the import names")
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val created = assertIs<ImportOutcome.Imported>(
                subject.importer.import(request(clauseId, subject.stage(), date, album.id)),
                "an ordinary photo imports into an album",
            ).created
            assertTrue(
                album.holds(created),
                "the photo is in the album it was imported into: it is filed in the commit that creates it",
            )
        }

        clause(
            "AN_IMPORT_INTO_A_MISSING_ALBUM_STILL_SAVES_IT",
            GalleryImportState.GRANTED_VALID_STAGED_ALBUM_GONE,
            covers = cells {
                on<GalleryImport>().answers(GalleryImport::import).with(ImportOutcome.Imported::class)
            },
        ) { subject ->
            val clauseId = "AN_IMPORT_INTO_A_MISSING_ALBUM_STILL_SAVES_IT"
            val date = PhotoLibrary.window(name, clauseId).seedDate
            val created = assertIs<ImportOutcome.Imported>(
                subject.importer.import(request(clauseId, subject.stage(), date, absentAlbumId(clauseId))),
                "an album the library no longer holds fails nothing: the photo is saved without it",
            ).created
            assertEquals(date, subject.library.captureDate(created), "the photo is in the library")
        }
    }
}

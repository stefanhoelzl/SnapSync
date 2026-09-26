package app.snapsync.contracts

import app.snapsync.services.staging.StagingService
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The states a [StagingService] can be found in, as far as a clause cares. Every path a clause names is relative. */
enum class StagedBytesState {
    /** There is nowhere durable to stage — on iOS, a process without the App-Group container. */
    UNAVAILABLE,

    /** A staging root exists and holds none of the clause's files. */
    EMPTY,

    /** The files [StagedBytesContract.stagedNames] names exist under the staging root. */
    STAGED,
}

/**
 * What staged downloaded bytes promise (`docs/architecture.md`; the port's KDoc carries why).
 *
 * The obligations are the ones a lost photo would turn on: an unavailable area **refuses** to locate a file rather
 * than naming a directory the release side cannot find; release is idempotent and tolerates missing files; and
 * [StagingService.allPresent] reports the fact of existence — any missing member answers `false`, an empty
 * list answers `true`.
 *
 * Paths are built from [StagingService.stagingRoot] and the clause id, so a binding seeds the same files the
 * clause then asks about, whatever root its implementation resolves.
 */
object StagedBytesContract : Contract<StagedBytesState, StagingService>("StagingService") {

    /** The file names a [StagedBytesState.STAGED] binding creates under the staging root, for [clauseId]. */
    fun stagedNames(clauseId: String) = listOf("$clauseId-a.bin", "$clauseId-b.bin")

    private fun StagingService.staged(clauseId: String) = stagedNames(clauseId).map { "${stagingRoot()}/$it" }

    override val clauses = clauses {

        clause("UNAVAILABLE_LOCATE_REFUSES", StagedBytesState.UNAVAILABLE) { bytes ->
            assertFails("staging into a directory nobody chose loses every photo written there") {
                bytes.locate("${bytes.stagingRoot()}/UNAVAILABLE_LOCATE_REFUSES.bin")
            }
        }

        clause("EMPTY_LOCATE_NAMES_A_FILE_UNDER_THE_ROOT_AND_CREATES_NOTHING", StagedBytesState.EMPTY) { bytes ->
            val path = "${bytes.stagingRoot()}/EMPTY_LOCATE_NAMES_A_FILE_UNDER_THE_ROOT_AND_CREATES_NOTHING.bin"
            assertEquals(bytes.locate(path), bytes.locate(path), "a platform path is a function of the staged path")
            assertFalse(bytes.allPresent(listOf(path)))
        }

        clause("EMPTY_STAGING_ROOT_IS_STABLE", StagedBytesState.EMPTY) { bytes ->
            assertEquals(bytes.stagingRoot(), bytes.stagingRoot())
        }

        clause("EMPTY_NO_PATHS_ARE_ALL_PRESENT", StagedBytesState.EMPTY) { bytes ->
            assertTrue(bytes.allPresent(emptyList()), "an empty list carries no evidence and answers true")
        }

        clause("EMPTY_UNSTAGED_PATHS_ARE_NOT_PRESENT", StagedBytesState.EMPTY) { bytes ->
            assertFalse(bytes.allPresent(bytes.staged("EMPTY_UNSTAGED_PATHS_ARE_NOT_PRESENT")))
        }

        clause("EMPTY_RELEASING_MISSING_FILES_IS_A_NOOP", StagedBytesState.EMPTY) { bytes ->
            val paths = bytes.staged("EMPTY_RELEASING_MISSING_FILES_IS_A_NOOP")
            bytes.release(paths)
            assertFalse(bytes.allPresent(paths))
        }

        clause("STAGED_ARE_PRESENT", StagedBytesState.STAGED) { bytes ->
            assertTrue(bytes.allPresent(bytes.staged("STAGED_ARE_PRESENT")))
        }

        clause("STAGED_ONE_MISSING_IS_NOT_ALL_PRESENT", StagedBytesState.STAGED) { bytes ->
            val (first, second) = bytes.staged("STAGED_ONE_MISSING_IS_NOT_ALL_PRESENT")
            bytes.release(listOf(first))
            assertFalse(bytes.allPresent(listOf(first, second)), "one ingested resource is evidence enough")
            assertTrue(bytes.allPresent(listOf(second)), "release removes only what it was given")
        }

        clause("STAGED_RELEASE_IS_IDEMPOTENT", StagedBytesState.STAGED) { bytes ->
            val paths = bytes.staged("STAGED_RELEASE_IS_IDEMPOTENT")
            bytes.release(paths)
            bytes.release(paths)
            assertFalse(bytes.allPresent(paths.take(1)))
            assertFalse(bytes.allPresent(paths.drop(1)))
        }
    }
}

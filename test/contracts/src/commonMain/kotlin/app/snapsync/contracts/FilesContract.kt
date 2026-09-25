package app.snapsync.contracts

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The states the clause's files can be found in, as far as a clause cares. */
enum class FilesState {
    /** Both areas are reachable and hold none of the clause's files. */
    EMPTY,

    /** [FilesContract.path] in the SHARED area holds [FilesContract.seed]; nothing in PRIVATE. */
    HOLDING,

    /** [FilesContract.path] in the SHARED area exists and this process may not read it. */
    DENIED,

    /** The SHARED area cannot be reached (on iOS, a build without the App-Group entitlement). */
    UNAVAILABLE,
}

/**
 * What `Files` promises (`docs/architecture.md`; the port's KDoc carries why). The obligation a false leave
 * would turn on is the first: **only a missing file is not found** — a present file that cannot be read is
 * denied, never absent, and an unreachable area is neither.
 *
 * Paths derive from the clause id and are nested, so a write proves it creates its parent directories.
 */
object FilesContract : Contract<FilesState, Files>("Files") {

    /** The clause's file, relative to its area. */
    fun path(clauseId: String) = "contract/$clauseId/file.bin"

    /** What a [FilesState.HOLDING] binding writes: ASCII lines, several times [SMALL]. */
    fun seed(clauseId: String): ByteArray =
        (1..LINES).joinToString(separator = "") { "$clauseId line $it\n" }.encodeToByteArray()

    const val SMALL = 16
    private const val LINES = 12

    override val clauses = clauses {

        clause("EMPTY_READ_IS_NOT_FOUND", FilesState.EMPTY) { files ->
            FileArea.entries.forEach {
                assertEquals(FileResult.NotFound, files.read(it, path("EMPTY_READ_IS_NOT_FOUND")), "$it")
                assertEquals(FileResult.NotFound, files.readTail(it, path("EMPTY_READ_IS_NOT_FOUND"), SMALL), "$it")
            }
        }

        clause("EMPTY_EXISTS_IS_FALSE_AND_DELETE_IS_NOT_FOUND", FilesState.EMPTY) { files ->
            val p = path("EMPTY_EXISTS_IS_FALSE_AND_DELETE_IS_NOT_FOUND")
            FileArea.entries.forEach {
                assertEquals(FileResult.Ok(false), files.exists(it, p), "$it")
                assertEquals(FileResult.NotFound, files.delete(it, p), "$it")
            }
        }

        clause("EMPTY_WRITE_CREATES_ITS_DIRECTORIES_AND_READS_BACK", FilesState.EMPTY) { files ->
            val p = path("EMPTY_WRITE_CREATES_ITS_DIRECTORIES_AND_READS_BACK")
            val bytes = seed("EMPTY_WRITE_CREATES_ITS_DIRECTORIES_AND_READS_BACK")
            FileArea.entries.forEach {
                assertEquals(FileResult.Ok(Unit), files.write(it, p, bytes), "$it")
                assertContentEquals(bytes, assertIs<FileResult.Ok<ByteArray>>(files.read(it, p)).value, "$it")
                assertEquals(FileResult.Ok(true), files.exists(it, p), "$it")
            }
        }

        clause("EMPTY_AN_EMPTY_FILE_IS_NOT_ABSENT", FilesState.EMPTY) { files ->
            val p = path("EMPTY_AN_EMPTY_FILE_IS_NOT_ABSENT")
            files.write(FileArea.SHARED, p, ByteArray(0))
            assertEquals(0, assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, p)).value.size)
            val tail = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(files.readTail(FileArea.SHARED, p, SMALL)).value
            assertEquals(0, tail.bytes.size)
            assertTrue(!tail.cut)
        }

        clause("EMPTY_LOCATE_NAMES_EACH_AREA_APART", FilesState.EMPTY) { files ->
            val p = path("EMPTY_LOCATE_NAMES_EACH_AREA_APART")
            val shared = assertIs<FileResult.Ok<String>>(files.locate(FileArea.SHARED, p)).value
            val private = assertIs<FileResult.Ok<String>>(files.locate(FileArea.PRIVATE, p)).value
            assertTrue(shared != private, "two areas are two places")
            assertEquals(shared, assertIs<FileResult.Ok<String>>(files.locate(FileArea.SHARED, p)).value, "stable")
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, p), "locating creates nothing")
        }

        clause("HOLDING_READ_IS_THE_BYTES_AND_AREAS_ARE_APART", FilesState.HOLDING) { files ->
            val p = path("HOLDING_READ_IS_THE_BYTES_AND_AREAS_ARE_APART")
            assertContentEquals(
                seed("HOLDING_READ_IS_THE_BYTES_AND_AREAS_ARE_APART"),
                assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, p)).value,
            )
            assertEquals(FileResult.NotFound, files.read(FileArea.PRIVATE, p), "the private area is another place")
        }

        clause("HOLDING_READ_TAIL_TAKES_THE_END", FilesState.HOLDING) { files ->
            val id = "HOLDING_READ_TAIL_TAKES_THE_END"
            val bytes = seed(id)
            val small = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(files.readTail(FileArea.SHARED, path(id), SMALL)).value
            assertContentEquals(bytes.copyOfRange(bytes.size - SMALL, bytes.size), small.bytes)
            assertTrue(small.cut, "a tail that began after the first byte says so")
            val whole = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(files.readTail(FileArea.SHARED, path(id), bytes.size * 2)).value
            assertContentEquals(bytes, whole.bytes)
            assertTrue(!whole.cut)
        }

        clause("HOLDING_WRITE_REPLACES", FilesState.HOLDING) { files ->
            val p = path("HOLDING_WRITE_REPLACES")
            files.write(FileArea.SHARED, p, "short".encodeToByteArray())
            assertEquals("short", assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, p)).value.decodeToString())
        }

        clause("HOLDING_DELETE_REMOVES_IT", FilesState.HOLDING) { files ->
            val p = path("HOLDING_DELETE_REMOVES_IT")
            assertEquals(FileResult.Ok(Unit), files.delete(FileArea.SHARED, p))
            assertEquals(FileResult.NotFound, files.read(FileArea.SHARED, p))
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, p))
        }

        clause("DENIED_IS_NEVER_NOT_FOUND", FilesState.DENIED) { files ->
            val p = path("DENIED_IS_NEVER_NOT_FOUND")
            assertIs<FileResult.Denied>(
                files.read(FileArea.SHARED, p),
                "a present file read as absent is a false leave when it is the config",
            )
            assertIs<FileResult.Denied>(files.readTail(FileArea.SHARED, p, SMALL))
            assertEquals(FileResult.Ok(true), files.exists(FileArea.SHARED, p), "it is there")
        }

        clause("UNAVAILABLE_AREA_IS_NEITHER_FOUND_NOR_ABSENT", FilesState.UNAVAILABLE) { files ->
            val p = path("UNAVAILABLE_AREA_IS_NEITHER_FOUND_NOR_ABSENT")
            assertEquals(FileResult.AreaUnavailable, files.read(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.readTail(FileArea.SHARED, p, SMALL))
            assertEquals(FileResult.AreaUnavailable, files.write(FileArea.SHARED, p, ByteArray(1)))
            assertEquals(FileResult.AreaUnavailable, files.delete(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.exists(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.locate(FileArea.SHARED, p))
        }
    }
}

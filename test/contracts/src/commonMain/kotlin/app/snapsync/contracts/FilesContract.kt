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

    /**
     * [FilesContract.directory] in the SHARED area exists and holds [FilesContract.path], and this process may not
     * list it.
     */
    DENIED_DIRECTORY,

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

    /** The clause's directory, relative to its area. */
    fun directory(clauseId: String) = "contract/$clauseId"

    /** The clause's file, relative to its area: inside [directory]. */
    fun path(clauseId: String) = "${directory(clauseId)}/file.bin"

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

        clause("EMPTY_RANGE_IS_NOT_FOUND_AND_APPEND_CREATES_ITS_DIRECTORIES", FilesState.EMPTY) { files ->
            val p = path("EMPTY_RANGE_IS_NOT_FOUND_AND_APPEND_CREATES_ITS_DIRECTORIES")
            FileArea.entries.forEach {
                assertEquals(FileResult.NotFound, files.readRange(it, p, 0, SMALL), "$it")
                assertEquals(FileResult.Ok(Unit), files.append(it, p, "one,".encodeToByteArray()), "$it")
                assertEquals(FileResult.Ok(Unit), files.append(it, p, "two".encodeToByteArray()), "$it")
                assertEquals(
                    "one,two",
                    assertIs<FileResult.Ok<ByteArray>>(files.read(it, p)).value.decodeToString(),
                    "$it",
                )
            }
        }

        clause("EMPTY_AN_EMPTY_FILE_IS_NOT_ABSENT", FilesState.EMPTY) { files ->
            val p = path("EMPTY_AN_EMPTY_FILE_IS_NOT_ABSENT")
            files.write(FileArea.SHARED, p, ByteArray(0))
            assertEquals(0, assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, p)).value.size)
            val tail = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(
                files.readTail(FileArea.SHARED, p, SMALL),
            ).value
            assertEquals(0, tail.bytes.size)
            assertTrue(!tail.cut)
        }

        clause("EMPTY_A_DIRECTORY_IS_NOT_A_FILE", FilesState.EMPTY) { files ->
            val id = "EMPTY_A_DIRECTORY_IS_NOT_A_FILE"
            FileArea.entries.forEach {
                files.write(it, path(id), ByteArray(1))
                assertIs<FileResult.Failed>(
                    files.read(it, directory(id)),
                    "$it: a directory is there, so never NotFound, and it is not this process's to be Denied",
                )
            }
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
            val small = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(
                files.readTail(FileArea.SHARED, path(id), SMALL),
            ).value
            assertContentEquals(bytes.copyOfRange(bytes.size - SMALL, bytes.size), small.bytes)
            assertTrue(small.cut, "a tail that began after the first byte says so")
            val whole = assertIs<FileResult.Ok<app.snapsync.model.FileTail>>(
                files.readTail(FileArea.SHARED, path(id), bytes.size * 2),
            ).value
            assertContentEquals(bytes, whole.bytes)
            assertTrue(!whole.cut)
        }

        clause("HOLDING_A_RANGE_IS_THE_BYTES_AT_ITS_OFFSET", FilesState.HOLDING) { files ->
            val id = "HOLDING_A_RANGE_IS_THE_BYTES_AT_ITS_OFFSET"
            val bytes = seed(id)
            fun range(
                offset: Long,
                max: Int,
            ) = assertIs<FileResult.Ok<ByteArray>>(files.readRange(FileArea.SHARED, path(id), offset, max)).value
            assertContentEquals(bytes.copyOfRange(0, SMALL), range(0, SMALL), "from the start")
            assertContentEquals(bytes.copyOfRange(SMALL, 2 * SMALL), range(SMALL.toLong(), SMALL), "from an offset")
            assertContentEquals(
                bytes.copyOfRange(bytes.size - 3, bytes.size),
                range(bytes.size - 3L, SMALL),
                "short only at the end",
            )
            assertEquals(0, range(bytes.size.toLong(), SMALL).size, "at the end: nothing")
            assertEquals(0, range(bytes.size + 100L, SMALL).size, "past the end: nothing")
            assertEquals(
                FileResult.NotFound,
                files.readRange(FileArea.PRIVATE, path(id), 0, SMALL),
                "the private area is another place",
            )
        }

        clause("HOLDING_APPEND_EXTENDS_THE_FILE", FilesState.HOLDING) { files ->
            val id = "HOLDING_APPEND_EXTENDS_THE_FILE"
            assertEquals(FileResult.Ok(Unit), files.append(FileArea.SHARED, path(id), "tail".encodeToByteArray()))
            assertContentEquals(
                seed(id) + "tail".encodeToByteArray(),
                assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, path(id))).value,
            )
        }

        clause("HOLDING_WRITE_REPLACES", FilesState.HOLDING) { files ->
            val p = path("HOLDING_WRITE_REPLACES")
            files.write(FileArea.SHARED, p, "short".encodeToByteArray())
            assertEquals(
                "short",
                assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, p)).value.decodeToString(),
            )
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
            assertIs<FileResult.Denied>(files.readRange(FileArea.SHARED, p, 0, SMALL))
            assertEquals(FileResult.Ok(true), files.exists(FileArea.SHARED, p), "it is there")
        }

        clause("DENIED_DIRECTORY_LIST_IS_DENIED_NEVER_EMPTY", FilesState.DENIED_DIRECTORY) { files ->
            assertIs<FileResult.Denied>(
                files.list(FileArea.SHARED, directory("DENIED_DIRECTORY_LIST_IS_DENIED_NEVER_EMPTY")),
                "a directory that could not be listed answered as empty reads as 'nothing was staged'",
            )
        }

        clause("UNAVAILABLE_AREA_IS_NEITHER_FOUND_NOR_ABSENT", FilesState.UNAVAILABLE) { files ->
            val p = path("UNAVAILABLE_AREA_IS_NEITHER_FOUND_NOR_ABSENT")
            assertEquals(FileResult.AreaUnavailable, files.read(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.readTail(FileArea.SHARED, p, SMALL))
            assertEquals(FileResult.AreaUnavailable, files.readRange(FileArea.SHARED, p, 0, SMALL))
            assertEquals(FileResult.AreaUnavailable, files.write(FileArea.SHARED, p, ByteArray(1)))
            assertEquals(FileResult.AreaUnavailable, files.append(FileArea.SHARED, p, ByteArray(1)))
            assertEquals(FileResult.AreaUnavailable, files.delete(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.exists(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.locate(FileArea.SHARED, p))
            assertEquals(FileResult.AreaUnavailable, files.move(FileArea.SHARED, p, "$p.moved"))
            assertEquals(FileResult.AreaUnavailable, files.list(FileArea.SHARED, "contract"))
            assertEquals(FileResult.AreaUnavailable, files.adopt("/handed/by/the/platform.bin", FileArea.SHARED, p))
        }

        clause("EMPTY_LIST_IS_EVERY_FILE_BENEATH_AT_ANY_DEPTH", FilesState.EMPTY) { files ->
            val dir = "contract/EMPTY_LIST_IS_EVERY_FILE_BENEATH_AT_ANY_DEPTH"
            val inside = listOf("$dir/a.bin", "$dir/deeper/b.bin", "$dir/deeper/still/c.bin")
            FileArea.entries.forEach {
                inside.forEach { path -> files.write(it, path, ByteArray(1)) }
                files.write(it, "$dir-beside/d.bin", ByteArray(1))
                assertEquals(
                    FileResult.Ok(inside),
                    files.list(it, dir),
                    "$it: sorted, relative to the area, directories not listed",
                )
                files.delete(it, "$dir/deeper/b.bin")
                assertEquals(
                    FileResult.Ok(inside - "$dir/deeper/b.bin"),
                    files.list(it, dir),
                    "$it: a deleted file is gone",
                )
            }
        }

        clause("EMPTY_LIST_OF_A_MISSING_DIRECTORY_IS_EMPTY", FilesState.EMPTY) { files ->
            FileArea.entries.forEach {
                assertEquals(
                    FileResult.Ok(emptyList()),
                    files.list(it, "contract/EMPTY_LIST_OF_A_MISSING_DIRECTORY_IS_EMPTY"),
                    "$it: no directory holds no files",
                )
            }
        }

        clause("EMPTY_MOVE_REPLACES_AND_CREATES_ITS_DIRECTORIES", FilesState.EMPTY) { files ->
            val id = "EMPTY_MOVE_REPLACES_AND_CREATES_ITS_DIRECTORIES"
            val from = path(id)
            val to = "contract/$id/nested/moved.bin"
            FileArea.entries.forEach {
                files.write(it, to, ByteArray(1))
                files.write(it, from, seed(id))
                assertEquals(FileResult.Ok(Unit), files.move(it, from, to), "$it")
                assertContentEquals(
                    seed(id),
                    assertIs<FileResult.Ok<ByteArray>>(files.read(it, to)).value,
                    "$it: replaced",
                )
                assertEquals(FileResult.Ok(false), files.exists(it, from), "$it: the source is gone")
            }
        }

        clause("EMPTY_MOVE_OF_NOTHING_IS_NOT_FOUND", FilesState.EMPTY) { files ->
            val id = "EMPTY_MOVE_OF_NOTHING_IS_NOT_FOUND"
            FileArea.entries.forEach {
                assertEquals(FileResult.NotFound, files.move(it, path(id), "contract/$id/moved.bin"), "$it")
            }
        }

        clause("EMPTY_ADOPT_TAKES_OVER_A_PLATFORM_FILE", FilesState.EMPTY) { files ->
            // A platform path the process was handed: this adapter's own located file stands for the OS's temp file.
            val id = "EMPTY_ADOPT_TAKES_OVER_A_PLATFORM_FILE"
            val handed = path(id)
            val to = "contract/$id/staged/adopted.bin"
            files.write(FileArea.PRIVATE, handed, seed(id))
            val osPath = assertIs<FileResult.Ok<String>>(files.locate(FileArea.PRIVATE, handed)).value
            files.write(FileArea.SHARED, to, ByteArray(1))
            assertEquals(FileResult.Ok(Unit), files.adopt(osPath, FileArea.SHARED, to))
            assertContentEquals(seed(id), assertIs<FileResult.Ok<ByteArray>>(files.read(FileArea.SHARED, to)).value)
            assertEquals(FileResult.Ok(false), files.exists(FileArea.PRIVATE, handed), "the platform's file was moved")
        }

        clause("EMPTY_ADOPT_OF_NOTHING_IS_NOT_FOUND", FilesState.EMPTY) { files ->
            val id = "EMPTY_ADOPT_OF_NOTHING_IS_NOT_FOUND"
            val osPath = assertIs<FileResult.Ok<String>>(files.locate(FileArea.PRIVATE, path(id))).value
            assertEquals(FileResult.NotFound, files.adopt(osPath, FileArea.SHARED, "contract/$id/adopted.bin"))
        }
    }
}

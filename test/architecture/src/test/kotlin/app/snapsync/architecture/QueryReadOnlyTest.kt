package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A query only reads** (`docs/patterns.md`, "Queries"): `:domain:query` answers what the screen asks — a membership,
 * an invite's event, a switch's facts, a request's status — and changes nothing it reads. Its one state is its own:
 * the request statuses the work that answers a request writes. A write of the data layer's — an entry, a deletion, an
 * update, a share change, a leave, a forget, a check's stamp, a leave's confirmation, a rename, an enrolment, a leave
 * told to the backend — belongs to a use case an entry point runs.
 *
 * The compiler cannot hold this: the query sees the data layer, readers and writers alike, because a repository is
 * both. So the writes are named here, by the members that make them.
 */
class QueryReadOnlyTest {

    private val writes = Regex(
        """\.(enter|markDeleted|update|reconfigure|leave|forget|stamp|confirm|rename|enroll|notifyLeaving)\(""",
    )

    @Test
    fun `the query layer calls no write of the data layer`() {
        val sources = SourceScan.kotlinFiles().filter { "/domain/query/src/commonMain/" in it.path }
        assertTrue(sources.isNotEmpty(), "no query source scanned — the module moved")
        val calls = sources.flatMap { source ->
            source.text.lines().mapIndexedNotNull { index, line ->
                writes.find(line.substringBefore("//"))?.let { "${source.path}:${index + 1}: ${it.value}" }
            }
        }
        assertEquals(emptyList(), calls, "a query writes — move the write into a use case an entry point runs")
    }
}

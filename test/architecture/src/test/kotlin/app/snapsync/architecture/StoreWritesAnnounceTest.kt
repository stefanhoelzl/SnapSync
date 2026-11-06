package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every write of a store is announced** (`docs/patterns.md`, "The change feed"): what the screen shows follows the
 * stores' change signal (`StoreChanges`), raised when SQLDelight notifies the driver's listeners — once a statement
 * outside a transaction has run, once a transaction has committed. That holds for the generated queries only: a
 * statement written straight onto the driver notifies nobody, so a screen reading the rows it changed would stay stale
 * with no error anywhere. The stores therefore make no raw statement.
 */
class StoreWritesAnnounceTest {

    private val raw = Regex("""\bdriver\.execute\(|\.execute\(\s*null\s*,|\.executeQuery\(\s*null\s*,""")

    @Test
    fun `no store writes past the generated queries`() {
        val sources = SourceScan.kotlinFiles().filter {
            "/domain/data/src/commonMain/" in it.path || "/domain/services/src/commonMain/" in it.path
        }
        assertTrue(sources.isNotEmpty(), "no store source scanned — the modules moved")
        val statements = sources.flatMap { source ->
            source.text.lines().mapIndexedNotNull { index, line ->
                raw.find(line.substringBefore("//"))?.let { "${source.path}:${index + 1}: ${it.value}" }
            }
        }
        assertEquals(emptyList(), statements, "a raw statement announces nothing — write it as a generated query")
    }
}

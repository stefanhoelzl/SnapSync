package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Ports are the I/O boundary — no file mixes a port interface with a technology impl** (spec
 * `module-architecture`; promoted from the migration beacon's mixed-files row at the finale, per
 * the beacon's own contract). A file declaring an `interface` next to a Ktor or SQLDelight import
 * is the seed of the pre-migration shape — a port and its technology impl cohabiting, so a move
 * of either drags the other and the interface silently stops being a boundary. Ports live in
 * `:domain`'s `ports/` (no technology imports — the zone allowlist already forbids them there);
 * impls live in adapter modules, in impl-only files.
 */
class MixedPortImplTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun sources(): List<File> = listOf("adapter", "domain", "ui").flatMap { root ->
        File(repoRoot, root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/" in it.path }
            .toList()
    }

    @Test
    fun `no file declares an interface beside a Ktor or SQLDelight import`() {
        val files = sources()
        assertTrue(files.isNotEmpty(), "mixed-file gate scanned zero sources — the roots moved")
        val mixed = files.filter { f ->
            val t = f.readText()
            Regex("""^\s*interface\s""", RegexOption.MULTILINE).containsMatchIn(t) &&
                (t.contains("import io.ktor") || t.contains("import app.cash.sqldelight"))
        }.map { it.toRelativeString(repoRoot) }
        assertTrue(
            mixed.isEmpty(),
            "port interface mixed with a technology impl — split the file (interface into ports/, " +
                "impl into its adapter module):\n  " + mixed.sorted().joinToString("\n  "),
        )
    }
}

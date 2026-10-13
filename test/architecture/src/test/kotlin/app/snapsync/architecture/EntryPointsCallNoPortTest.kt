package app.snapsync.architecture

import app.snapsync.ports.Port
import kotlin.reflect.full.isSuperclassOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The entry points call no port** (`docs/patterns.md`, "Entry points"). `:domain:entrypoints` sees the ports zone for
 * one reason: the arguments its deliveries carry are the ports' vocabulary (`FinishedTransfer`, `SessionRelaunch`, …).
 * A port itself — an interface extending [Port] — is never named there: an entry point orders steps, and what a step
 * touches outside the process it reaches through a service. The compile edge cannot say "vocabulary only", so this
 * says it, by reading every import of the zone.
 */
class EntryPointsCallNoPortTest {

    @Test
    fun `no entry point imports a port`() {
        val files = SourceScan.kotlinFiles().filter { it.path.startsWith("/domain/entrypoints/src/commonMain/") }
        assertTrue(files.isNotEmpty(), "found no entry point source — the scan is broken")
        val imports = files.flatMap { file ->
            Regex("""^import app\.snapsync\.ports\.(\w+)$""", RegexOption.MULTILINE).findAll(file.text)
                .map { file.path to it.groupValues[1] }
        }
        assertTrue(imports.isNotEmpty(), "the entry points import none of the ports' vocabulary — the scan is broken")
        val ports = imports.filter { (_, name) ->
            Port::class.isSuperclassOf(
                Class.forName("app.snapsync.ports.$name").kotlin,
            )
        }
        assertTrue(
            ports.isEmpty(),
            "an entry point names a port — reach it through a service instead:\n  " +
                ports.joinToString("\n  ") { (path, name) -> "$path imports $name" },
        )
    }
}

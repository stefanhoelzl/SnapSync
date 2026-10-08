package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Platform code lives in an adapter, behind a port** (`docs/architecture.md`, "Ports are the I/O boundary named for
 * the need"): the core (`:domain:*`) and the UI (`:ui:*`) hold NO main source set but `commonMain`. A `jvmMain`,
 * `iosMain` or `androidMain` there is a platform's behaviour no port names, which one platform's tests cannot reach and
 * the coverage gates measure for one platform only — what `:ui:components`' `expect fun DateFormats` was, until it became
 * the `DateFormatting` port.
 *
 * Derived, never listed: the modules are the build's own include set (`settings.gradle.kts`, as [ModuleSetTest] reads
 * it), and a module's source sets are the directories under its `src/`. A directory named `…Test` is a test source set,
 * and those are unrestricted — a test may bind to a platform.
 */
class PlatformCodeContainmentTest {

    private val repoRoot = SourceScan.repoRoot

    /** The `:domain:*` and `:ui:*` modules the build includes. */
    private val guarded: List<String> = Regex("""include\("([^"]+)"\)""")
        .findAll(File(repoRoot, "settings.gradle.kts").readText())
        .map { it.groupValues[1] }
        .filter { it.startsWith(":domain:") || it.startsWith(":ui:") }
        .toList()

    @Test
    fun `the core and the UI hold no main source set but commonMain`() {
        val offenders = guarded.flatMap { module ->
            platformSourceSets(File(repoRoot, module.removePrefix(":").replace(':', '/'))).map { "  $module: src/$it" }
        }
        assertTrue(
            offenders.isEmpty(),
            "platform code in the core or the UI. Put it in an adapter (`:adapter:*`) behind a port in :domain:ports, " +
                "and hand the port in through a root — as `DateFormatting` is.\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the gate scanned every module (non-vacuity floor)`() {
        assertTrue(guarded.size >= 10, "platform-code gate: found only ${guarded.size} :domain:/:ui: modules")
        val unscanned = guarded.filterNot {
            File(repoRoot, it.removePrefix(":").replace(':', '/') + "/src/commonMain").isDirectory
        }
        assertTrue(
            unscanned.isEmpty(),
            "platform-code gate: these modules have no src/commonMain — re-point it: $unscanned",
        )
    }

    @Test
    fun `the scan sees a platform source set and passes tests`() {
        val module = File.createTempFile("module", "").apply {
            delete()
            mkdirs()
        }
        try {
            listOf("commonMain", "commonTest", "jvmTest", "androidDeviceTest", "jvmMain", "iosArm64Main", "empty")
                .forEach { File(module, "src/$it").mkdirs() }
            listOf("commonMain", "commonTest", "jvmTest", "androidDeviceTest", "jvmMain", "iosArm64Main")
                .forEach { File(module, "src/$it/A.kt").writeText("") }
            assertEquals(listOf("iosArm64Main", "jvmMain"), platformSourceSets(module))
        } finally {
            module.deleteRecursively()
        }
    }

    /** [module]'s main source sets other than `commonMain` that hold a file. */
    private fun platformSourceSets(module: File): List<String> =
        module.resolve("src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "commonMain" && !it.name.endsWith("Test") }
            .filter { dir -> dir.walk().any { it.isFile } }
            .map { it.name }
            .sorted()
}

package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **No app-only platform API in extension-linked Kotlin source** (capability `architecture-guards`;
 * decision record: `establish-target-architecture`).
 *
 * Forcing proof this gate exists at all: **Kotlin/Native does not model `NS_EXTENSION_UNAVAILABLE`**
 * — cinterop ignores ObjC availability attributes, so `platform.UIKit.UIApplication.sharedApplication`
 * compiles clean in ANY `iosMain`, including source the appex links. The module split prevents only
 * CROSS-module leaks (the extension simply doesn't depend on app-only modules); an in-module
 * reference sails through every compiler and surfaces as an App Store validation rejection or a
 * runtime abort in the field. Expiry trigger: Kotlin/Native gaining extension-availability checking.
 *
 * Scope is DERIVED: every Kotlin source under the modules the extension framework links today
 * (`:app:ios:extension`, `:adapter:ios:ext-safe`). New files are born in scope; the
 * non-vacuity twin catches a rename emptying the scan.
 */
class ExtensionSafetyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * Roots the extension binary links. Grows as the migration creates adapters; the
     * `photokit-discovery` root left the list when migration step 4 moved its two files into
     * `adapter/ios/ext-safe` and deleted the module (coverage moved, it did not shrink).
     */
    private val extensionLinkedRoots = listOf(
        "app/ios/extension",
        "adapter/ios/ext-safe",
    )

    private val forbidden = listOf("platform.UIKit", "platform.BackgroundTasks")

    private fun scanned(): List<File> = extensionLinkedRoots
        .map { File(repoRoot, it) }
        .filter { it.isDirectory }
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/" in it.path }
                .toList()
        }

    @Test
    fun `the guard actually scanned extension-linked sources`() {
        assertTrue(
            scanned().isNotEmpty(),
            "extension-safety guard scanned nothing — the extension-linked roots have moved; update " +
                "extensionLinkedRoots or this gate fails open forever",
        )
    }

    @Test
    fun `extension-linked source references no app-only platform API`() {
        val hits = scanned().flatMap { file ->
            val text = file.readText()
            forbidden.filter { it in text }.map { "${file.toRelativeString(repoRoot)} references $it" }
        }
        assertTrue(
            hits.isEmpty(),
            "App-only API in extension-linked source — no compiler will ever flag this " +
                "(Kotlin/Native ignores NS_EXTENSION_UNAVAILABLE); it fails at App Store validation " +
                "or aborts in the field:\n  ${hits.joinToString("\n  ")}",
        )
    }
}

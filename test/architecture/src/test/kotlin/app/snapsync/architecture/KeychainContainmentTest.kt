package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * **All Keychain access lives in `:adapter:ios:ext-safe`** (capability `architecture-guards`).
 *
 * This is one half of a two-part proof. Containment (here) says every `SecItem*` call is in one module;
 * that module's own tests say every query it builds carries `kSecAttrAccessibleAfterFirstUnlock`.
 * Together they establish the property that actually matters — *every Keychain item in the app is
 * readable by background work on a locked device* — which neither half establishes alone.
 *
 * Why a test and not a compile error: the Material 3 containment rule is enforced by the Gradle
 * dependency graph (only `:ui:components` declares the dependency, so elsewhere the import does
 * not resolve). There is no such lever here — `platform.Security` is a Kotlin/Native **platform
 * library**, ambient in every `iosMain` source set, with no dependency to withhold. So the invariant is
 * enforced by reading the source.
 *
 * Why source text and not a linter: the rule must catch a **fully-qualified** call —
 * `platform.Security.SecItemAdd(query, null)` imports nothing — and detekt's `ForbiddenImport` sees only
 * imports, while `ForbiddenMethodCall` needs type resolution, which detekt does not have for
 * Kotlin/Native source sets. Reading the file reaches `iosMain` from a JVM test because it compiles
 * nothing; a PSI parser bought no more than that here (see [SourceScan]).
 */
class KeychainContainmentTest {

    /**
     * The module allowed to touch the Keychain. Everything else must borrow it. Migration step 4
     * moved the impls (`IosKeychain`, `KeychainDeviceIdentity`) from `:domain:keychain` into the
     * extension-safe adapter module (step 12 then deleted `:domain:keychain` entirely) — the
     * containment property is unchanged, only its address.
     */
    private val owningModule = "/adapter/ios/ext-safe/"

    private val forbidden = listOf(
        "platform.Security", // the import, and any fully-qualified reference
        "SecItemAdd(",
        "SecItemCopyMatching(",
        "SecItemUpdate(",
        "SecItemDelete(",
    )

    private fun productionFiles() = SourceScan.kotlinFiles()
        .filterNot { it.path.contains("/test/architecture/") } // this file names the forbidden tokens

    @Test
    fun `keychain access appears only in domain-keychain`() {
        val offenders = productionFiles()
            .filterNot { it.path.contains(owningModule) }
            .flatMap { file -> forbidden.filter { it in file.text }.map { "${file.path} names $it" } }
        kotlinAssertTrue(
            offenders.isEmpty(),
            "no SecItem access outside $owningModule — every Keychain item the app persists must be " +
                "readable by background work on a locked device, which is provable only if every call " +
                "site is in the one module whose tests assert the accessibility class:\n  " +
                offenders.sorted().joinToString("\n  "),
        )
    }

    /**
     * A guard that scans nothing passes vacuously. The scan is now plain file reads, so no parser
     * version can drop a file silently out of scope — but a moved directory still can. Assert the scope
     * is real: if this fails, the guard above is not guarding, and that must break the build loudly rather
     * than report success.
     */
    @Test
    fun `the guard actually scanned the ios sources it claims to guard`() {
        val files = productionFiles()
        val iosFiles = files.filter { it.path.contains("/iosMain/") }

        kotlinAssertTrue(files.size > 100, "expected the whole repo in scope, saw ${files.size} files")
        kotlinAssertTrue(
            iosFiles.size > 20,
            "expected the iosMain source sets in scope (that is where SecItem could hide), saw ${iosFiles.size}",
        )
        // And the owning module's real Keychain code must itself be visible — if THIS is not in scope,
        // nothing is, and the containment test above would pass no matter what anyone wrote.
        kotlinAssertTrue(
            files.any { it.path.contains(owningModule) && it.text.contains("SecItemAdd(") },
            "the guard cannot see :adapter:ios:ext-safe's own SecItemAdd call — the scope is broken",
        )
    }
}

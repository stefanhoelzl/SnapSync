package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * **All Keychain access lives in `:domain:keychain`** (capability `architecture-guards`).
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
 * Why Konsist and not a linter: the rule must catch a **fully-qualified** call —
 * `platform.Security.SecItemAdd(query, null)` imports nothing — and detekt's `ForbiddenImport` sees only
 * imports, while `ForbiddenMethodCall` needs type resolution, which detekt does not have for
 * Kotlin/Native source sets. Konsist parses source (PSI), so it reads `iosMain` from a JVM test.
 */
class KeychainContainmentTest {

    /**
     * The module allowed to touch the Keychain. Everything else must borrow it. Migration step 4
     * moved the impls (`IosKeychain`, `KeychainDeviceIdentity`) from `:domain:keychain` into the
     * extension-safe adapter module — the containment property is unchanged, only its address.
     */
    private val owningModule = "/adapter/ios/ext-safe/"

    private val forbidden = listOf(
        "platform.Security", // the import, and any fully-qualified reference
        "SecItemAdd(",
        "SecItemCopyMatching(",
        "SecItemUpdate(",
        "SecItemDelete(",
    )

    private fun productionFiles() = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .filterNot { it.path.contains("/test/architecture/") } // this file names the forbidden tokens

    @Test
    fun `keychain access appears only in domain-keychain`() {
        productionFiles()
            .filterNot { it.path.contains(owningModule) }
            .assertTrue(testName = "no SecItem access outside :domain:keychain") { file ->
                forbidden.none { token -> file.text.contains(token) }
            }
    }

    /**
     * A guard that scans nothing passes vacuously. Konsist embeds a Kotlin **2.0** PSI parser while this
     * repo is on Kotlin **2.4** — the same version lag that disqualified detekt 1.x — so a future language
     * feature could in principle make files fail to parse and drop silently out of scope. Assert the scope
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
            "the guard cannot see :domain:keychain's own SecItemAdd call — the scope is broken",
        )
    }
}

package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * **`:domain:status` never names an engine type** (capability `architecture-guards`, holding
 * `sync-status`'s "Module placement plugs the engine leak").
 *
 * `ledger-free-status` freed status from the ledger and nothing structural keeps it free. Engine **is** on
 * status's compile classpath and unavoidably so: `:domain:gallery` declares `api(project(":domain:engine"))`
 * — it must, because `GalleryResourceEnumerator.enumerate()` returns `List<Resource>` — and status depends
 * on gallery, so `api` propagates. A file under `:domain:status` importing `LedgerWriter` compiles today.
 * That is measured, not feared: a probe importing it compiled clean, which is how the spec's old claim
 * ("`:domain:engine` is not on its compile classpath") was found to be false after weeks in the contract.
 *
 * Why a test and not the dependency graph: the graph is the usual lever here — `:domain:presentation`
 * really cannot see engine, because status→gallery is `implementation` and propagation stops. There is no
 * such lever for status itself while gallery's public API returns an engine type. Withholding the
 * dependency is not available; reading the source is.
 *
 * Why source text and not imports alone: a fully-qualified `app.snapsync.engine.LedgerBackend` imports
 * nothing. Konsist parses source (PSI), so it sees both forms — the same reason
 * [KeychainContainmentTest] uses it.
 *
 * What this deliberately does **not** forbid: status *using* an engine type by inference.
 * `OwnDeviceGalleryStatusSource` calls `enumerate()` and reads the returned resources' `assetId` and
 * `metadata` while naming nothing. That is the enumeration seam working as designed — gallery's API returns
 * engine's `Resource` and status consumes gallery's API. The rule is about status **reaching for** engine,
 * which is what reaching back for the ledger looks like.
 */
class StatusEngineBoundaryTest {

    /** The module that must stay engine-free in its source, whatever its classpath carries. */
    private val guardedModule = "/domain/status/"

    /** The package no status file may name. `LedgerWriter`, `LedgerBackend`, `Resource` all live under it. */
    private val forbidden = "app.snapsync.engine"

    private fun statusProductionFiles() = Konsist
        .scopeFromProject()
        .files
        .filterNot { it.path.contains("/build/") }
        .filterNot { it.path.contains("/test/architecture/") } // this file names the forbidden token
        .filter { it.path.contains(guardedModule) }
        .filterNot { it.path.contains("Test/") || it.path.contains("/commonTest/") }

    @Test
    fun `no status source names an engine type`() {
        statusProductionFiles()
            .assertTrue(testName = "no `app.snapsync.engine` reference in :domain:status") { file ->
                !file.text.contains(forbidden)
            }
    }

    /**
     * A guard that scans nothing passes vacuously — and this one filters hard enough to scan nothing by
     * accident (a module rename, a source-set move). Konsist also embeds a Kotlin 2.0 PSI parser while this
     * repo is on 2.4, so a future language feature could in principle drop files silently out of scope.
     * Assert the scope is real: if this fails, the guard above is not guarding, and that must break the
     * build loudly rather than report success.
     */
    @Test
    fun `the guard actually scanned the status sources it claims to guard`() {
        val files = statusProductionFiles()

        kotlinAssertTrue(files.size > 3, "expected :domain:status's sources in scope, saw ${files.size} files")
        // And the real projection must itself be visible. If THIS is not in scope, nothing is, and the
        // rule above would pass no matter what anyone wrote into status.
        kotlinAssertTrue(
            files.any { it.name == "LedgerBackedSyncStatusSource" },
            "expected LedgerBackedSyncStatusSource in scope, saw ${files.map { it.name }}",
        )
    }
}

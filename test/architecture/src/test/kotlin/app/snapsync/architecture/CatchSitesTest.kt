package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Failures are contained at the runtime's catch sites** (`docs/patterns.md`, "Containment"). What a run, a step or a
 * pass throws is caught where the runtime started it — the run's containment, `isolated`, the process scope's handler,
 * and a pass's outcome as a value — and reported once, as an `Error`. Nowhere else does production code catch
 * everything, and outside the adapters nothing catches at all: an adapter turns a platform's throw into a value (a
 * typed catch is its business), and above it every expected failure IS a value (`docs/architecture.md`, "A port
 * answers every expected failure as a value").
 *
 * The old zones still hold the catch-alls they were written with (`runCatchingCancellable`, `bestEffort`, `contained`,
 * a broad or typed `catch`): [BASELINE] counts them per file, EXACT in both directions — a new one
 * fails, and one removed must be removed here too, so the count only falls. Phase 4 empties it as each zone moves under
 * entry points and sequences, and deletes the helpers with the last use.
 *
 * Read as text, comments stripped: a catch site is a syntactic shape. [CatchGateTest] holds the other half, that every
 * site still here keeps cancellation.
 */
class CatchSitesTest {

    private val broad = listOf(
        Regex("""catch\s*\(\s*\w+\s*:\s*(?:Throwable|Exception)\s*\)"""),
        Regex("""\brunCatching\s*\{"""),
        Regex("""\brunCatchingCancellable\b"""),
        Regex("""\.bestEffort\("""),
        Regex("""\.contained\("""),
    )

    /** Any `catch` clause, and the type it names — outside the adapters, a typed catch counts too. */
    private val typed = Regex("""catch\s*\(\s*\w+\s*:\s*([\w.]+)\s*\)""")

    private val notACatchAll = setOf(
        "Throwable",
        "Exception",
        "CancellationException",
        "kotlin.coroutines.cancellation.CancellationException",
        // The rethrow that passes an InvalidProgramState on, as the cancellation one passes cancellation on.
        "InvalidProgramState",
    )

    /** The sites themselves, and why each may catch everything. */
    private val sites = mapOf(
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/Runtime.kt" to
            "a run's containment, a synchronous delivery's, an answered delivery's ObjC boundary, the scope's handler",
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/Steps.kt" to
            "`isolated`: a step whose failure is reported and the run goes on",
        "/domain/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/SingleFlight.kt" to
            "a pass's end as a value for every waiter: its answer, its failure, or its cancellation",
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/objc/ObjCBoundary.kt" to
            "every Kotlin block and delegate method ObjC calls — nothing may unwind into ObjC frames",
        "/domain/model/src/commonMain/kotlin/app/snapsync/model/Catching.kt" to
            "the old zones' helpers themselves, deleted with their last use in phase 4",
    )

    private val roots = listOf("/domain/", "/adapter/", "/app/", "/ui/")

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return roots.any { path.startsWith(it) } && sourceSet.isNotEmpty() && !sourceSet.endsWith("Test") &&
            sourceSet != "test"
    }

    private fun catchAlls(path: String, text: String): Int {
        val code = text.lines().joinToString("\n") { it.substringBefore("//") }
        val broadCount = broad.sumOf { it.findAll(code).count() }
        val typedCount = if (path.startsWith("/adapter/")) {
            0
        } else {
            typed.findAll(code).count { it.groupValues[1] !in notACatchAll }
        }
        return broadCount + typedCount
    }

    @Test
    fun `catch-alls stand only at the runtime's sites and the old zones' shrinking baseline`() {
        val files = SourceScan.kotlinFiles().filter { isProduction(it.path) }
        assertTrue(files.size >= 300, "scanned only ${files.size} production files — the source tree moved")
        val found = files.filterNot { it.path in sites }
            .associate { it.path to catchAlls(it.path, it.text) }
            .filterValues { it > 0 }
            .toSortedMap()
        assertEquals(
            BASELINE.toSortedMap(),
            found,
            "the catch sites drifted from the baseline. A new catch-all: contain the failure at a step (`isolated`) " +
                "or answer it as a value. One removed: lower its count here (the baseline only shrinks).",
        )
    }

    @Test
    fun `every licensed site still exists`() {
        val paths = SourceScan.kotlinFiles().map { it.path }.toSet()
        sites.keys.forEach { assertTrue(it in paths, "catch site $it no longer exists — drop the licence") }
    }

    private companion object {
        /** Path → the catch-alls it still holds. May only shrink; phase 4 empties it. */
        val BASELINE: Map<String, Int> = mapOf(
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/device/AndroidDeviceConditions.kt" to 2,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/download/AndroidDownload.kt" to 5,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/gallery/AndroidGallery.kt" to 2,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/gallery/AndroidGalleryReader.kt" to 3,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/gallery/MediaStoreImport.kt" to 6,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/gallery/MotionPhotoBuilder.kt" to 2,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/push/AndroidPushNotifications.kt" to 3,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/storage/AndroidDatabases.kt" to 2,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/systemui/AndroidSystemUi.kt" to 2,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/work/AndroidUpload.kt" to 3,
            "/adapter/android/src/androidMain/kotlin/app/snapsync/android/work/AndroidWake.kt" to 3,
            "/adapter/generic/app/src/commonMain/kotlin/app/snapsync/http/KtorHttpTransport.kt" to 1,
            "/adapter/generic/app/src/jvmMain/kotlin/app/snapsync/databases/JdbcDatabases.kt" to 2,
            "/adapter/generic/mock/src/commonMain/kotlin/app/snapsync/mock/BackendRouter.kt" to 5,
            "/adapter/generic/mock/src/commonMain/kotlin/app/snapsync/mock/EventKeyLedger.kt" to 3,
            "/adapter/generic/mock/src/commonMain/kotlin/app/snapsync/mock/InMemoryDatabases.kt" to 2,
            "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/device/IosDeviceConditions.kt" to 2,
            "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/download/LivePhotoFromMotionPhoto.kt" to 4,
            "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/crypto/IosCrypto.kt" to 2,
            "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/databases/IosDatabases.kt" to 3,
            "/app/desktop/src/main/kotlin/app/snapsync/desktop/MirrorHarness.kt" to 4,
            "/app/desktop/src/main/kotlin/app/snapsync/desktop/WorldInspectorController.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/album/AlbumCoordinator.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/diagnostics/CollectDiagnosticDump.kt" to 3,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/download/DownloadController.kt" to 8,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/download/StoreDownloadStatusSource.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/membership/ReceivedPhotoAdoption.kt" to 3,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/membership/ShareSetLoad.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/push/PushRegistration.kt" to 5,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/status/LedgerCountsSource.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/status/OwnDeviceGalleryStatusSource.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/status/StatusCountsPoller.kt" to 1,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/status/StatusRefresh.kt" to 2,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/upload/CycleGateRead.kt" to 3,
            // Moved from EventCompletion with its read of the manifest record (phase 4.2): a record that does not decode
            // is no settled share. Its own phase (4.4c) answers the decode as a value.
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/upload/ShareSettlement.kt" to 1,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/upload/StoredUploadSettle.kt" to 1,
            "/domain/feature/src/commonMain/kotlin/app/snapsync/feature/upload/UploadCycle.kt" to 4,
            "/domain/model/src/commonMain/kotlin/app/snapsync/model/ConfigFile.kt" to 2,
            "/domain/model/src/commonMain/kotlin/app/snapsync/model/Footprint.kt" to 1,
            "/domain/presentation/src/commonMain/kotlin/app/snapsync/presentation/StatusContainerHost.kt" to 5,
            // The invite link's codec moved from model with its catches (phase 4.2): a library's parse failure, read
            // as "no invite".
            "/domain/format/src/commonMain/kotlin/app/snapsync/format/EventKeyText.kt" to 1,
            "/domain/format/src/commonMain/kotlin/app/snapsync/format/InviteLinkText.kt" to 2,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/album/AlbumMapService.kt" to 5,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/backend/Listings.kt" to 3,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/backend/Membership.kt" to 2,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/backend/PushTokenPublisher.kt" to 2,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/crypto/DownloadOpening.kt" to 1,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/crypto/EventKeys.kt" to 4,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/crypto/UploadSealing.kt" to 1,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/databases/LegacyRecords.kt" to 5,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/gallery/AlbumExclusions.kt" to 2,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/manifest/DeviceManifestService.kt" to 6,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/upload/SessionTransfer.kt" to 1,
            "/domain/services/src/commonMain/kotlin/app/snapsync/services/upload/UploadStandings.kt" to 2,
            "/domain/wire/src/commonMain/kotlin/app/snapsync/wire/runtime/Wire.kt" to 1,
        )
    }
}

package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Failures are contained at the runtime's catch sites** (`docs/patterns.md`, "Containment"). What a run, a step or a
 * pass throws is caught where the runtime started it — the run's containment, `isolated`, the process scope's handler,
 * and a pass's outcome as a value — and reported once, as an `Error`. Nowhere else in the core does anything catch
 * everything; above the adapters every expected failure IS a value (`docs/architecture.md`, "A port answers every
 * expected failure as a value").
 *
 * **A typed catch of a library's failure is a translation**, allowed where a layer translates a library — data, format
 * and wire (kotlinx-serialization and the platform's text parsers answer a bad input only by throwing a documented type)
 * — and in the adapters, whose job is turning a platform's throw into a value. Anywhere else in the core it counts.
 *
 * **A catch-all is a named site.** Outside the runtime's own sites, only an adapter may catch everything, at a platform
 * boundary whose failures the platform does not document, through `PlatformCalls` — and each such file is listed in
 * [NAMED] with how many it holds and what they guard, EXACT in both directions: a new one fails until it is named, and
 * one removed must be removed here, so the list only shrinks. The desktop harness (test equipment) is held the same way.
 *
 * Read as text, comments stripped: a catch site is a syntactic shape. [CatchGateTest] holds the other half, that every
 * site still here keeps cancellation.
 */
class CatchSitesTest {

    private val broad = listOf(
        Regex("""catch\s*\(\s*\w+\s*:\s*(?:Throwable|Exception)\s*\)"""),
        Regex("""\brunCatching\s*\{"""),
        Regex("""\bPlatformCalls\.attempt(?:Suspending)?\b"""),
    )

    /** Any `catch` clause, and the type it names — where typed catches are not a translation, they count too. */
    private val typed = Regex("""catch\s*\(\s*\w+\s*:\s*([\w.]+)\s*\)""")

    private val notACatchAll = setOf(
        "Throwable",
        "Exception",
        "CancellationException",
        "kotlin.coroutines.cancellation.CancellationException",
        // The rethrow that passes an InvalidProgramState on, as the cancellation one passes cancellation on.
        "InvalidProgramState",
    )

    /** Where a typed catch of a library's failure translates it, and so is that layer's business. */
    private val translating = listOf(
        "/adapter/",
        "/app/desktop/",
        "/core/data/",
        "/core/format/",
        "/core/wire/",
    )

    /** The runtime's sites themselves, and why each may catch everything. */
    private val sites = mapOf(
        "/core/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/Runtime.kt" to
            "a run's containment, a synchronous delivery's, an answered delivery's ObjC boundary, the scope's handler",
        "/core/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/Steps.kt" to
            "`isolated`: a step whose failure is reported and the run goes on",
        "/core/scheduling/src/commonMain/kotlin/app/snapsync/scheduling/SingleFlight.kt" to
            "a pass's end as a value for every waiter: its answer, its failure, or its cancellation",
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/objc/ObjCBoundary.kt" to
            "every Kotlin block and delegate method ObjC calls — nothing may unwind into ObjC frames",
        "/adapter/generic/app/src/commonMain/kotlin/app/snapsync/platform/Catching.kt" to
            "the adapters' helpers themselves",
    )

    private val roots = listOf("/core/", "/adapter/", "/app/", "/ui/")

    private fun isProduction(path: String): Boolean {
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return roots.any { path.startsWith(it) } && sourceSet.isNotEmpty() && !sourceSet.endsWith("Test") &&
            sourceSet != "test"
    }

    private fun catchAlls(path: String, text: String): Int {
        val code = text.lines().joinToString("\n") { it.substringBefore("//") }
        val broadCount = broad.sumOf { it.findAll(code).count() }
        val typedCount = if (translating.any { path.startsWith(it) }) {
            0
        } else {
            typed.findAll(code).count { it.groupValues[1] !in notACatchAll }
        }
        return broadCount + typedCount
    }

    @Test
    fun `the core catches nothing but typed library failures, and every catch-all is a named site`() {
        val files = SourceScan.kotlinFiles().filter { isProduction(it.path) }
        assertTrue(files.size >= 300, "scanned only ${files.size} production files — the source tree moved")
        val found = files.filterNot { it.path in sites }
            .associate { it.path to catchAlls(it.path, it.text) }
            .filterValues { it > 0 }
            .toSortedMap()
        assertEquals(
            NAMED.mapValues { it.value.count }.toSortedMap(),
            found,
            "the catch sites drifted from the named ones. In the core: answer the failure as a value, or contain it at " +
                "a step (`isolated`). In an adapter: catch the type the platform documents; a boundary that documents " +
                "none goes through `PlatformCalls` and is named here, with what it guards. One removed: lower its count " +
                "here (the list only shrinks).",
        )
        assertTrue(NAMED.keys.none { it.startsWith("/core/") }, "the core names no catch-all")
    }

    @Test
    fun `every licensed site still exists`() {
        val paths = SourceScan.kotlinFiles().map { it.path }.toSet()
        sites.keys.forEach { assertTrue(it in paths, "catch site $it no longer exists — drop the licence") }
    }

    /** A file's catch-alls: how many, and what each guards. */
    private class Named(val count: Int, @Suppress("unused") val guards: String)

    private companion object {
        private const val ANDROID = "/adapter/android/src/androidMain/kotlin/app/snapsync/android"
        private const val IOS_APP = "/adapter/ios/app-only/src/iosMain/kotlin/app/snapsync"
        private const val IOS_EXT = "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync"
        private const val GENERIC = "/adapter/generic"
        private const val DESKTOP = "/app/desktop/src/main/kotlin/app/snapsync/desktop"

        /** Path → its catch-alls and what they guard. May only shrink. */
        val NAMED: Map<String, Named> = mapOf(
            "$ANDROID/device/AndroidDeviceConditions.kt" to
                Named(1, "each device fact's platform read (BatteryManager, PowerManager, UsageStatsManager)"),
            "$ANDROID/download/AndroidDownload.kt" to Named(
                4,
                "DownloadManager's enqueue, query and restart; a completion broadcast's queries around its delivery",
            ),
            "$ANDROID/gallery/AndroidGallery.kt" to Named(1, "MediaStore's album write"),
            "$ANDROID/gallery/AndroidGalleryReader.kt" to Named(2, "MediaStore's move and its reads"),
            "$ANDROID/gallery/MediaStoreImport.kt" to Named(5, "MediaStore's insert, publish, orphan sweep and decode"),
            "$ANDROID/gallery/MotionPhotoBuilder.kt" to Named(1, "assembling a Motion Photo from the platform's bytes"),
            "$ANDROID/push/AndroidPushNotifications.kt" to Named(1, "starting Firebase"),
            "$ANDROID/storage/AndroidDatabases.kt" to Named(1, "SQLite's open"),
            "$ANDROID/systemui/AndroidSystemUi.kt" to Named(1, "launching the app's Settings page"),
            "$ANDROID/work/AndroidWake.kt" to Named(1, "WorkManager's enqueue"),
            "$GENERIC/app/src/commonMain/kotlin/app/snapsync/http/KtorHttpTransport.kt" to
                Named(1, "one exchange over the engine: IO, timeouts and the engine's own failures"),
            "$GENERIC/app/src/jvmMain/kotlin/app/snapsync/databases/JdbcDatabases.kt" to Named(1, "JDBC's SQLite open"),
            "$GENERIC/mock/src/commonMain/kotlin/app/snapsync/mock/InMemoryDatabases.kt" to
                Named(1, "the in-memory SQLite open, each platform's driver throwing its own type"),
            "$IOS_APP/device/IosDeviceConditions.kt" to Named(1, "each device fact's platform read"),
            "$IOS_APP/download/LivePhotoFromMotionPhoto.kt" to Named(1, "taking a Motion Photo apart (ImageIO, AVFoundation)"),
            "$IOS_EXT/crypto/IosCrypto.kt" to Named(1, "CryptoKit's open of a seal that does not authenticate"),
            "$IOS_EXT/device/IosProcessConditions.kt" to Named(1, "each process fact's platform read"),
            "$IOS_EXT/databases/IosDatabases.kt" to Named(2, "SQLiter's open and its version read"),
            "$DESKTOP/MirrorHarness.kt" to Named(3, "the mirrored phone's control channel: its advertisement, state and taps"),
        )
    }
}

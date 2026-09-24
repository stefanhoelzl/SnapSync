package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The walk memo is composed in the app process only** (capability `ios-photokit-upload`, "In-extension
 * discovery by full enumeration"; capability `sync-ledger`, "An unchanged library is answered from the walk memo";
 * decision record `changes/own-work-per-wake`, D9).
 *
 * The upload extension runs under a 32 MB memory limit whose overrun is a jetsam kill and a relaunch loop, not an
 * error, and it holds nothing across `process()` calls — so it SHALL walk afresh every time and hold no memo. Both
 * processes link `:domain`, where the memo type lives, so the module graph cannot keep it out; this pins the two
 * steps that do, exact in both directions:
 *
 *  - a `WalkMemo` is constructed in production source in **one** place, the app composition's
 *    `appUploadDiscovery`;
 *  - `appUploadDiscovery` is called in production source from **one** file, the app's uploader — never from the
 *    extension's root or the shared `uploadCore`, which the extension also calls.
 *
 * The iOS token read (`PhotoKitLibraryChangeTokenRead`) is kept out by linkage besides: it lives in
 * `:adapter:ios:app-only`, which the extension does not link.
 */
class WalkMemoContainmentTest {

    /** Hand-written production Kotlin: any `*Main` source set, plus the rig's (compiled into `:app:ios`). */
    private val production = SourceScan.kotlinFiles().filter { src ->
        val sourceSet = src.path.substringAfter("/src/", "").substringBefore('/')
        sourceSet.endsWith("Main") || sourceSet == "rig"
    }

    private fun filesMatching(pattern: Regex): Set<String> =
        production.filter { src ->
            src.text.lines().any { line ->
                val code = line.substringBefore("//")
                !code.trimStart().startsWith("*") && pattern.containsMatchIn(code)
            }
        }.mapTo(sortedSetOf()) { it.path }

    @Test
    fun `the memo is constructed only by the app composition`() {
        assertEquals(
            setOf(APP_COMPOSITION),
            filesMatching(Regex("""(?<!class )\bWalkMemo\(""")),
            "a WalkMemo built anywhere but `appUploadDiscovery` can reach a composition the upload extension runs",
        )
    }

    @Test
    fun `only the app's uploader binds the memoised discovery`() {
        assertEquals(
            setOf(APP_UPLOADER),
            filesMatching(Regex("""(?<!fun )\bappUploadDiscovery\(""")),
            "the memoised discovery is the app process's binding only: the extension walks afresh on every " +
                "`process()` call, and `uploadCore` is shared with it",
        )
    }

    private companion object {
        const val APP_COMPOSITION = "/domain/compose/src/commonMain/kotlin/app/snapsync/compose/AppUploadDiscovery.kt"
        const val APP_UPLOADER = "/app/ios/src/iosMain/kotlin/app/snapsync/ios/UrlSessionUploadController.kt"
    }
}

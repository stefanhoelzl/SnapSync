package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **No PhotoKit media ABI in `:domain`** (capability `architecture-guards`; law: `gallery-status`,
 * *The domain reads neutral asset facts*).
 *
 * `:domain` is platform-free by module, but "platform-free" is enforced by the *dependency* graph, and a
 * bare `Long` bitmask carries no dependency. The `PHAssetMediaSubtype` bits and the `PHAssetMediaType`
 * integers used to sit in `model/` as plain constants, interpreted there — compiling everywhere,
 * meaning nothing off iOS, and verifiable nowhere: a `commonTest` asserting `1 shl 2` against a copy of
 * `1 shl 2` passes whether or not it matches the SDK. That is the drift `RuntimeIdentityTest` exists to
 * catch for OS-held literals, in a place it could not see.
 *
 * The interpretation now lives in `:adapter:ios:ext-safe`, where `PhotoKitAssetFactsTest` asserts each
 * constant against the real SDK symbol on the simulator. This guard keeps it from drifting back: the
 * domain decides admission on neutral booleans and an area, and names no PhotoKit value.
 *
 * Prose is exempt — the policy's doc comments necessarily *explain* what the platform can and cannot
 * express, and forbidding that would push the reasoning out of the file it belongs in.
 */
class PhotoKitAbiContainmentTest {

    /** PhotoKit media symbols that must not appear in executable `:domain` code. */
    private val forbidden = listOf(
        "PHAssetMediaSubtype",
        "PHAssetMediaType",
        "mediaSubtypes",
        "SUBTYPE_SCREENSHOT",
        "SUBTYPE_SCREEN_RECORDING",
        "EXCLUDED_SUBTYPE_MASK",
        "MEDIA_TYPE_IMAGE",
        "MEDIA_TYPE_VIDEO",
    )

    @Test
    fun `domain source names no PhotoKit media ABI`() {
        val root = File(ZoneGates.domainSrc, "commonMain/kotlin/app/snapsync")
        assertTrue(root.isDirectory, "no :domain source found at $root — has the module moved?")

        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore("//")
                    if (code.trimStart().let { it.startsWith("*") || it.startsWith("/*") }) return@mapNotNull null
                    val hit = forbidden.firstOrNull { code.contains(it) } ?: return@mapNotNull null
                    "${file.name}:${i + 1} names `$hit` — the PhotoKit media model is interpreted in " +
                        "`:adapter:ios:ext-safe` (`PhotoKitAssetFacts.kt`), where its constants are pinned " +
                        "against the SDK and tested on the simulator. `:domain` decides on neutral " +
                        "`AssetFacts` (capability `gallery-status`)."
                }
            }
            .toList()
        ZoneGates.assertNoViolations("photokit-abi-containment", violations)
    }

    /** Vacuity check: the interpretation must actually exist where the guard says it does. */
    @Test
    fun `the iOS adapter holds the interpretation`() {
        val file = File(
            ZoneGates.repoRoot,
            "adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/gallery/PhotoKitAssetFacts.kt",
        )
        assertTrue(file.isFile, "PhotoKitAssetFacts.kt not found at $file — has the interpretation moved?")
        val text = file.readText()
        assertTrue(
            text.contains("SUBTYPE_SCREENSHOT") && text.contains("mediaSubtypes"),
            "PhotoKitAssetFacts.kt no longer reads the subtype bitmask — the guard above would then pass " +
                "vacuously while the interpretation had moved somewhere unpinned",
        )
    }
}

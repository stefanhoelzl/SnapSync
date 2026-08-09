package app.snapsync.architecture

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The platform-vocabulary pin** (capability `architecture-guards`).
 *
 * For every Apple enumeration an adapter decodes with a **fallback arm**, this pins the complete set of
 * constants that enumeration declares, with their values, and fails on any delta.
 *
 * It is the inward mirror of `RuntimeIdentityTest`: that pins literals **we** hold which the OS also
 * holds, so we cannot strand devices in the field; this pins literals **Apple** holds which we encode,
 * so Apple cannot widen a vocabulary we decode without saying so. It is also the third leg of a trio
 * that `PhotoKitAbiContainmentTest` and the adapters' simulator tests already form:
 *
 * ```
 *   PhotoKitAbiContainmentTest   the ABI stays OUT of :domain          (our source)
 *   PhotoKit*Test (simulator)    our constants match the SDK symbols   (our mapping)
 *   THIS GUARD                   the SDK's declared SET is unchanged   (Apple's vocabulary)
 * ```
 *
 * The first two cannot see an enumeration **growing**: a table naming four of five cases and a table
 * naming four of six are indistinguishable to both. That gap is what this closes, and it is the gap
 * "The platform-identifier gate" already confesses when it says a decoder over another system's values
 * "SHALL NOT be assumed caught".
 *
 * **Why a fallback arm is unavoidable, and therefore why this guard exists.** cinterop renders `NS_ENUM`
 * as a typealias over `NSInteger` plus loose `const val`s — never a Kotlin `enum class` — so a `when`
 * over one can never be compiler-exhaustive and always needs an `else`. The compiler can only check
 * what is in our source; it has no notion of "the set of constants Apple declares". This pin supplies
 * the exhaustiveness the language cannot.
 *
 * **Source of truth: the Kotlin/Native platform klib**, not a vendor header, a documentation page, or a
 * device observation. That klib is the compiler's own input, so it states exactly what our source sees
 * — and reading it needs no Mac and no Xcode, so this runs on Linux inside `./gradlew build` rather
 * than on macOS CI alone. Because the platform klibs ship prebuilt inside the Kotlin/Native
 * distribution, the declared set changes when the **Kotlin/Native version** changes: this fails on the
 * `libs.versions.toml` bump that introduces the new vocabulary, which is the earliest moment anyone
 * can see it.
 *
 * ⚠️ **What a green run does NOT mean.** This describes what the SDK *declares*, never what the OS
 * *returns*. A device may hand back a value no header carries, and the klib reflects the SDK the
 * Kotlin/Native distribution was built against rather than the iOS version on the device. The
 * decoders' fallback arms therefore remain load-bearing and must keep handling an unrecognised value
 * safely. Only a device measurement settles what the runtime actually produces.
 *
 * Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings` (design D5).
 */
class PlatformVocabularyPinTest {

    /**
     * The pinned inventory — the contract of record. Adding, removing or re-valuing an entry is a spec
     * change to `architecture-guards`' "The platform-vocabulary pin" requirement, deliberately.
     *
     * Each entry is an enumeration some adapter decodes with a fallback arm, so a case Apple adds would
     * otherwise be silently absorbed.
     */
    private val pinned = listOf(
        // Decoded by `photoKitJobState` (:adapter:ios:ext-safe, capability `ios-photokit-upload`).
        // Consequence of an untaught case: the terminal-job drain adjudicates it as a retry-spent
        // failure — safe (idempotent PUT, at-least-once) but wrong.
        PinnedEnum(
            framework = "Photos",
            prefix = "PHAssetResourceUploadJobState",
            decodedBy = "photoKitJobState (adapter/ios/ext-safe/…/upload/PhotoKitJobMapping.kt)",
            constants = mapOf(
                "PHAssetResourceUploadJobStateRegistered" to 1L,
                "PHAssetResourceUploadJobStatePending" to 2L,
                "PHAssetResourceUploadJobStateFailed" to 3L,
                "PHAssetResourceUploadJobStateSucceeded" to 4L,
                "PHAssetResourceUploadJobStateCancelled" to 5L,
            ),
        ),
        // Decoded by `photoKitResourceRole` (:adapter:ios:ext-safe, capability `gallery-status`).
        // Consequence of an untaught case is worse than above: the fallback DROPS the resource, so an
        // untaught original resource type is a photo that never uploads, with no error anywhere.
        PinnedEnum(
            framework = "Photos",
            prefix = "PHAssetResourceType",
            decodedBy = "photoKitResourceRole (adapter/ios/ext-safe/…/gallery/PhotoKitResourceRole.kt)",
            constants = mapOf(
                "PHAssetResourceTypePhoto" to 1L,
                "PHAssetResourceTypeVideo" to 2L,
                "PHAssetResourceTypeAudio" to 3L,
                "PHAssetResourceTypeAlternatePhoto" to 4L,
                "PHAssetResourceTypeFullSizePhoto" to 5L,
                "PHAssetResourceTypeFullSizeVideo" to 6L,
                "PHAssetResourceTypeAdjustmentData" to 7L,
                "PHAssetResourceTypeAdjustmentBasePhoto" to 8L,
                "PHAssetResourceTypePairedVideo" to 9L,
                "PHAssetResourceTypeFullSizePairedVideo" to 10L,
                "PHAssetResourceTypeAdjustmentBasePairedVideo" to 11L,
                "PHAssetResourceTypeAdjustmentBaseVideo" to 12L,
                "PHAssetResourceTypePhotoProxy" to 19L,
            ),
        ),
    )

    private class PinnedEnum(
        val framework: String,
        val prefix: String,
        val decodedBy: String,
        val constants: Map<String, Long>,
    )

    @Test
    fun `every pinned Apple enumeration declares exactly the constants we decode`() {
        // Collect across ALL pinned enumerations before failing (the house idiom — see
        // `ZoneGates.assertNoViolations`): stopping at the first delta would hide a second one behind a
        // re-run, and a Kotlin bump can move more than one vocabulary at once.
        val deltas = pinned.groupBy { it.framework }.flatMap { (framework, entries) ->
            val declared = declaredConstants(framework)
            entries.mapNotNull { entry ->
                val actual = declared
                    .filterKeys {
                        it.startsWith(entry.prefix) &&
                            it.removePrefix(entry.prefix).firstOrNull()?.isUpperCase() == true
                    }
                    .toSortedMap()
                val expected = entry.constants.toSortedMap()
                if (actual == expected) null else delta(entry, expected, actual)
            }
        }
        assertTrue(
            deltas.isEmpty(),
            "platform-vocabulary pin (capability `architecture-guards`):\n\n" + deltas.joinToString("\n"),
        )
    }

    private fun delta(
        entry: PinnedEnum,
        expected: Map<String, Long>,
        actual: Map<String, Long>,
    ): String = buildString {
        appendLine("`${entry.prefix}` no longer matches the pinned set.")
        appendLine()
        (actual.keys - expected.keys).sorted().forEach {
            appendLine("  ADDED    $it = ${actual[it]}  ← teach the decoder, then pin it")
        }
        (expected.keys - actual.keys).sorted().forEach {
            appendLine("  REMOVED  $it (was ${expected[it]})")
        }
        (actual.keys intersect expected.keys).sorted()
            .filter { actual[it] != expected[it] }
            .forEach { appendLine("  RE-VALUED $it: ${expected[it]} → ${actual[it]}") }
        appendLine()
        appendLine("Decoded by: ${entry.decodedBy}")
        appendLine(
            "cinterop renders NS_ENUM as a typealias over NSInteger plus loose constants — never a " +
                "Kotlin `enum class` — so the decoder's `when` can never be compiler-exhaustive and an " +
                "added case would otherwise be absorbed by its fallback arm in silence. That is what " +
                "this pin exists to prevent, so teach the decoder before updating the list.",
        )
        appendLine(
            "Updating the pinned inventory is a spec change to `architecture-guards`' " +
                "\"The platform-vocabulary pin\" requirement — do it deliberately, not to make this green.",
        )
    }

    // ---- reading the toolchain's own platform metadata -------------------------------------------

    /**
     * Every `const val Long` the named platform framework's klib declares.
     *
     * Fails loudly rather than returning empty when the distribution, the tool, or the klib is missing:
     * "nothing declared" and "I could not look" have opposite consequences here, and a guard that
     * collapses them fails open.
     */
    private fun declaredConstants(framework: String): Map<String, Long> {
        val dist = konanDistribution()
        val klibTool = File(dist, "bin/klib")
        if (!klibTool.canExecute()) {
            fail("platform-vocabulary pin: no executable klib tool at $klibTool — cannot read the SDK vocabulary")
        }
        val klib = File(dist, "klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.$framework")
        if (!klib.exists()) {
            fail(
                "platform-vocabulary pin: no $framework platform klib at $klib. It ships inside the " +
                    "Kotlin/Native distribution, so this means the distribution is incomplete — not that " +
                    "the vocabulary is empty.",
            )
        }

        val process = ProcessBuilder(klibTool.absolutePath, "dump-metadata", klib.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("platform-vocabulary pin: `klib dump-metadata $framework` did not finish within 3 minutes")
        }
        if (process.exitValue() != 0) {
            fail("platform-vocabulary pin: `klib dump-metadata $framework` failed (${process.exitValue()}):\n$output")
        }

        // e.g. `public final const val PHAssetResourceTypePhoto: kotlin/Long /* = … */ /* = 1L */`
        // The VALUE is the last `/* = …L */` on the line; the earlier one is the typealias expansion.
        val constant = Regex("""^\s*public final const val (\w+):.*/\* = (-?\d+)L \*/\s*$""")
        val declared = output.lineSequence()
            .mapNotNull { constant.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
        if (declared.isEmpty()) {
            fail(
                "platform-vocabulary pin: parsed zero constants from the $framework klib. The tool's " +
                    "output format has changed — fix the parser rather than accepting an empty set, which " +
                    "would make every pin below pass vacuously.",
            )
        }
        return declared
    }

    /**
     * The Kotlin/Native distribution this build resolves, located by the Kotlin version declared in
     * `gradle/libs.versions.toml` (the source of truth for versions) rather than by a hardcoded path.
     */
    private fun konanDistribution(): File {
        val version = kotlinVersion()
        val dataDir = System.getenv("KONAN_DATA_DIR")?.let(::File)
            ?: File(System.getProperty("user.home"), ".konan")
        if (!dataDir.isDirectory) {
            fail("platform-vocabulary pin: no Kotlin/Native data directory at $dataDir (KONAN_DATA_DIR overrides)")
        }
        val candidates = dataDir.listFiles { f: File ->
            f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith("-$version")
        }.orEmpty()
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> fail(
                "platform-vocabulary pin: no `kotlin-native-prebuilt-*-$version` distribution in $dataDir. " +
                    "It is provisioned by any Kotlin/Native compilation (e.g. " +
                    "`./gradlew compileIosMainKotlinMetadata`), which `./gradlew build` performs.",
            )
            else -> fail(
                "platform-vocabulary pin: ${candidates.size} distributions match version $version in " +
                    "$dataDir (${candidates.joinToString { it.name }}) — cannot tell which the build uses",
            )
        }
    }

    private fun kotlinVersion(): String {
        val toml = File(ZoneGates.repoRoot, "gradle/libs.versions.toml")
        assertEquals(true, toml.isFile, "gradle/libs.versions.toml not found at $toml")
        return Regex("""^\s*kotlin\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(toml.readText())
            ?.groupValues?.get(1)
            ?: fail("platform-vocabulary pin: no `kotlin = \"…\"` entry in $toml")
    }
}

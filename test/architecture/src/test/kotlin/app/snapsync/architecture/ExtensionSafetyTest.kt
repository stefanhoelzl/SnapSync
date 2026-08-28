package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Extension-linked Kotlin references only permitted platform frameworks** (capability
 * `architecture-guards`; decision record: `establish-target-architecture`).
 *
 * Forcing proof this gate exists at all: **Kotlin/Native does not model `NS_EXTENSION_UNAVAILABLE`**
 * — cinterop ignores ObjC availability attributes, so `platform.UIKit.UIApplication.sharedApplication`
 * compiles clean in ANY `iosMain`, including source the appex links. The module split prevents only
 * CROSS-module leaks (the extension simply doesn't depend on app-only modules); an in-module
 * reference sails through every compiler and surfaces as an App Store validation rejection or a
 * runtime abort in the field.
 *
 * ## Why an ALLOWLIST, and why that is not merely a longer denylist
 *
 * This gate used to forbid two frameworks — `platform.UIKit` and `platform.BackgroundTasks` — out of
 * roughly two hundred. Every other app-only framework (`CarPlay`, `WidgetKit`, `UserNotificationsUI`, …)
 * passed. A denylist covers what somebody remembered, and is wrong by default.
 *
 * The obvious repair — enumerate what Apple marks `NS_EXTENSION_UNAVAILABLE` — is impossible, and that
 * was measured rather than assumed: `klib dump-metadata` of the `platform.UIKit` klib is 94,179 lines
 * containing **zero** extension-unavailability markers. cinterop drops the attribute entirely;
 * `UIApplication.sharedApplication` carries only `@kotlinx/cinterop/ObjCMethod`. The information does not
 * exist in any artifact the build can read.
 *
 * So the rule inverts. The extension links a small, bounded set of frameworks; everything else fails.
 * That covers all ~200 rather than 2, and **fails closed on novelty** — a framework nobody anticipated is
 * rejected without anyone having anticipated it. Note the allowlist is *coarser* than the attribute it
 * replaces: it forbids a whole framework where Apple marks only some members, which is the stricter
 * direction and the one a transcriber-shaped extension can afford.
 *
 * **Imports and qualified references, never raw text.** `platform` is also an ordinary variable name in
 * this codebase — `platform.retryJob(…)`, `platform.fetch(…)` on the `BackgroundTransfer` seam — so a
 * substring match reports member access on a local as a framework reference.
 *
 * **The scope is DERIVED** from the extension binary's project-dependency closure, so a module newly
 * linked into the appex is covered with no gate edit. It previously claimed to be derived while being a
 * hand-written two-element list.
 *
 * Expiry trigger: Kotlin/Native gaining extension-availability checking, at which point the compiler
 * supersedes this gate.
 */
class ExtensionSafetyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The extension binary. Its dependency closure is this gate's scope. */
    private val extensionModule = ":app:ios:extension"

    /**
     * The frameworks extension-linked source may reference. Each is present because the appex genuinely
     * needs it; adding one is a deliberate act with a reason, which is the whole point of an allowlist.
     */
    private val permitted = setOf(
        "Foundation",             // NSData/NSError/NSFileManager — the appex's whole I/O vocabulary
        "Photos",                 // PhotoKit: the upload-job subsystem the extension exists to serve
        "Security",              // Keychain: the attestation token the appex sends
        "posix",                  // errno and low-level file primitives behind the App-Group store
        "CoreFoundation",         // CF bridging under the Foundation calls
        "CoreCrypto",             // digest for the upload keys
        "UniformTypeIdentifiers", // MIME/UTI for the resources it uploads
        "DeviceCheck",            // App Attest key material
    )

    private fun moduleDir(path: String): File = File(repoRoot, path.removePrefix(":").replace(':', '/'))

    /** The transitive `project(...)` closure of [extensionModule], read from the build scripts. */
    private fun linkedModules(): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(extensionModule))
        while (queue.isNotEmpty()) {
            val module = queue.removeFirst()
            if (!seen.add(module)) continue
            val build = File(moduleDir(module), "build.gradle.kts")
            if (!build.isFile) continue
            val code = build.readLines().joinToString("\n") { it.substringBefore("//") }
            Regex("""project\(\s*"(:[^"]+)"\s*\)""").findAll(code)
                .map { it.groupValues[1] }
                .forEach { queue.addLast(it) }
        }
        return seen
    }

    /**
     * Extension-linked source in a NATIVE source set.
     *
     * Restricted to `ios*`/`apple*`/`native*` deliberately, and not merely as an optimisation: a
     * `commonMain` file cannot reference a platform framework at all — the reference does not resolve on
     * the JVM target and the module would not compile — so scanning common source can only produce false
     * positives. It produced them immediately: `platform` is an ordinary parameter name on the
     * `BackgroundTransfer` seam, so `UploadCycle`'s `platform.retryJob(…)` reads as a framework
     * reference to any pattern that does not know where it is looking.
     */
    private fun scanned(): List<File> = linkedModules()
        .map { moduleDir(it) }
        .filter { it.isDirectory }
        .flatMap { root ->
            File(root, "src").walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
                .filter { file ->
                    val sourceSet = file.toRelativeString(root).replace('\\', '/')
                        .removePrefix("src/").substringBefore('/')
                    sourceSet.startsWith("ios") || sourceSet.startsWith("apple") ||
                        sourceSet.startsWith("native")
                }
                .toList()
        }

    @Test
    fun `the extension's link closure is derived and non-empty`() {
        val modules = linkedModules()
        assertTrue(
            extensionModule in modules && modules.size > 1,
            "derived only $modules from $extensionModule's build script — the closure walk is broken, " +
                "and a gate that scans nothing fails open",
        )
        assertTrue(
            scanned().isNotEmpty(),
            "the extension-safety gate scanned no sources — the linked modules have moved",
        )
    }

    @Test
    fun `extension-linked source references only permitted platform frameworks`() {
        // A framework reference is either an import, or a QUALIFIED use — `platform.Foundation.NSData`,
        // `platform.posix.errno` — which always carries a further dot. Member access on a local named
        // `platform` (`platform.retryJob(…)`, `platform.createJob(…)` on the BackgroundTransfer seam)
        // never does, and that collision is real in `iosMain` as well as in common source: matching the
        // bare prefix reported twelve of the adapter's own seam calls as framework references.
        val importForm = Regex("""^\s*import\s+platform\.([A-Za-z][A-Za-z0-9_]*)""")
        val qualifiedForm = Regex("""\bplatform\.([A-Za-z][A-Za-z0-9_]*)\.""")
        val violations = scanned().flatMap { file ->
            file.readLines().withIndex().mapNotNull { (i, line) ->
                val code = line.substringBefore("//")
                if (code.trimStart().startsWith("*")) return@mapNotNull null
                val framework = importForm.find(code)?.groupValues?.get(1)
                    ?: qualifiedForm.find(code)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                if (framework in permitted) return@mapNotNull null
                "${file.toRelativeString(repoRoot)}:${i + 1} references platform.$framework"
            }
        }
        assertTrue(
            violations.isEmpty(),
            "extension-linked source references a platform framework outside the allowlist. No compiler " +
                "will ever flag this — Kotlin/Native ignores NS_EXTENSION_UNAVAILABLE — so it fails at " +
                "App Store validation or aborts in the field. If the appex genuinely needs the " +
                "framework, add it to `permitted` with the reason:\n  " + violations.joinToString("\n  "),
        )
    }
}

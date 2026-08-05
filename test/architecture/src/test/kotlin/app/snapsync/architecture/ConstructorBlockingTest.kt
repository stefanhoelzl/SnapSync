package app.snapsync.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Adapter constructors perform no blocking work** (capability `architecture-guards`; law:
 * `module-architecture`, "Dispatcher lanes are fixed by the composition").
 *
 * The composition lane governs coroutines, and construction is not one. The graph assembles on whichever
 * thread touches it first — a launch (on the lane) or the first render (on the main thread) — so a
 * blocking call in a constructor is a **race** between those two. A race is precisely why such a defect
 * is never observed in testing and shows up as a watchdog kill on someone else's phone.
 *
 * `by lazy` is not constructor work and is not flagged: it defers to first access, which is a different
 * question (and one the parked runtime sentinel is the right instrument for — see the change's design).
 *
 * A Konsist gate rather than a custom detekt rule: the invariant is a property of source text in
 * Kotlin/Native source sets, which is what every sibling gate here already reads, and a detekt rule for
 * it would need a rule-provider module to say the same thing.
 */
class ConstructorBlockingTest {

    private companion object {
        /** Bounded so a cycle cannot hang the gate; the observed chain is two hops. */
        const val MAX_CALL_DEPTH = 4
    }

    /**
     * Synchronous platform **calls** — PhotoKit XPC, Keychain, file and archive I/O. Handles are
     * deliberately absent: `NSFileManager.defaultManager` is a singleton accessor that does no work, and
     * flagging it would train readers to ignore this gate.
     */
    private val blockingForms = listOf(
        "PHAsset.fetch", "PHAssetCollection.fetch", "PHAssetResource.assetResources",
        "performChanges", "registerChangeObserver", "fetchPersistentChangesSinceToken",
        "SecItemAdd(", "SecItemCopyMatching(", "SecItemUpdate(", "SecItemDelete(",
        "contentsOfFile", "contentsAtPath", "createDirectoryAt", "fileExistsAtPath", "writeToFile",
        "NSKeyedArchiver.archived", "NSKeyedUnarchiver.unarchived",
    )

    /**
     * Grandfathered, not forgiven. `FileBackedConfigStore` reads the App-Group file (with a Keychain
     * fallback) in its constructor because the status container's first state is built from seams that
     * "hold their current truth synchronously…never a guess or a placeholder" — so removing the read
     * means either a placeholder first frame or reordering launch against first render across the Swift
     * boundary. That is a change of its own, with no test that could prove it; this entry records the
     * constraint so the exemption stays a decision rather than becoming an oversight.
     */
    private val grandfathered = mapOf(
        "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/config/FileBackedConfigStore.kt" to
            "seeds its StateFlow from the config file so the first frame renders real values",
    )

    /**
     * The two places that run during construction: `init` blocks and **eagerly initialised** class
     * properties. A computed getter (`get() = …`) runs on access, and `by lazy` on first touch — neither
     * is construction, so neither is flagged here.
     */
    private fun constructionText(file: com.lemonappdev.konsist.api.declaration.KoFileDeclaration): String {
        val initBlocks = Regex("""\n\s{4}init \{(.*?)\n\s{4}}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.text).joinToString("\n") { it.value }
        val eagerProperties = file.classes()
            .flatMap { it.properties() }
            .map { it.text }
            .filterNot { it.contains("by lazy") || it.contains("get()") }
            .joinToString("\n")
        val direct = withoutCoroutineBuilders(initBlocks + "\n" + eagerProperties)
        return direct + "\n" + calleeBodies(file, direct)
    }

    /**
     * Strip `launch { … }` / `async { … }` / `withContext(…) { … }` bodies: work handed to a coroutine
     * builder runs on a dispatcher, not during construction, so it is the composition lane's business
     * rather than this gate's. Without this, an `init` whose only statement is `scope.launch { … }` —
     * `PhotoSelectionSnapshotSource`, which is correct — reads as a constructor that blocks.
     */
    private fun withoutCoroutineBuilders(text: String): String {
        val builder = Regex("""\b(launch|async|withContext)\s*(\([^)]*\))?\s*\{""")
        var out = text
        while (true) {
            val match = builder.find(out) ?: return out
            var depth = 0
            var end = -1
            for (i in match.range.last until out.length) {
                if (out[i] == '{') depth++
                if (out[i] == '}') {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
            if (end < 0) return out.removeRange(match.range)
            out = out.removeRange(match.range.first..end)
        }
    }

    /**
     * Call-following, transitive **within the file** and depth-bounded. This is the difference between a
     * gate and a formality: the only real instance — `FileBackedConfigStore` — reaches its file read two
     * hops from the initialiser (`state` → `read()` → `readFileRaw()`), and both a direct-text check and
     * a one-hop check passed with the grandfather list **emptied**. Anyone extracting a helper would
     * evade a shallower gate by accident.
     *
     * Within the file only, and capped: this has no type information, so it matches by name. Following
     * across files would multiply that ambiguity; the shape that occurs is a private helper beside the
     * constructor that uses it.
     */
    private fun calleeBodies(
        file: com.lemonappdev.konsist.api.declaration.KoFileDeclaration,
        constructionText: String,
    ): String {
        val functions = file.classes().flatMap { it.functions() } + file.functions()
        val collected = StringBuilder()
        var frontier = constructionText
        val seen = mutableSetOf<String>()
        repeat(MAX_CALL_DEPTH) {
            val called = Regex("""\b(\w+)\(""").findAll(frontier).map { it.groupValues[1] }.toSet() - seen
            if (called.isEmpty()) return collected.toString()
            seen += called
            val bodies = functions.filter { it.name in called }.joinToString("\n") { it.text }
            collected.append(bodies).append("\n")
            frontier = withoutCoroutineBuilders(bodies)
        }
        return collected.toString()
    }

    @Test
    fun `no iOS adapter blocks during construction`() {
        val offenders = Konsist
            .scopeFromProject()
            .files
            .filterNot { it.path.contains("/build/") }
            .filter { it.path.contains("/adapter/ios/") }
            .filterNot { it.path.contains("/iosTest/") }
            .filterNot { file -> grandfathered.keys.any { file.path.endsWith(it) } }
            .filter { file -> blockingForms.any { form -> constructionText(file).contains(form) } }
            .map { it.path.substringAfterLast("/") }

        assertTrue(
            offenders.isEmpty(),
            "blocking platform calls run during construction in: $offenders — construction happens on " +
                "whichever thread assembles the graph, so this is a race with the first render. Move the " +
                "work behind a suspending call or `by lazy`.",
        )
    }

    @Test
    fun `the grandfather list stays minimal and reasoned`() {
        assertTrue(
            grandfathered.size == 1 && grandfathered.values.all { it.isNotBlank() },
            "the grandfather list grew or lost its reasons — it exists to hold ONE known instance, " +
                "not to absorb new ones: $grandfathered",
        )
    }
}

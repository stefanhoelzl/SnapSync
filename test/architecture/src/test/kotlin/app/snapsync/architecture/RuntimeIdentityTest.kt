package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Runtime identity is pinned** (capability `architecture-guards`; decision record:
 * `pin-runtime-identity-and-zone-gates`).
 *
 * Every literal here is a string the OS or the installed base holds on its side — App-Group
 * container id, Keychain (service, account) pairs, `NSUserDefaults` keys, DB filenames, the
 * device-manifest layout, OS-registered BGTask/URLSession identifiers, framework `baseName`s.
 * Changing one strands or corrupts state on devices already in the field; the worst case (the
 * device-id pair) mints a new device identity and corrupts the event union for every member of an
 * event, remotely unfixably. The migration moves every file these literals live in, and nothing
 * else asserts them (they are `iosMain` defaults, invisible to the JVM loop).
 *
 * Each pin asserts **exactly one** occurrence in production Kotlin with the exact value — so any
 * move that drops, duplicates, or re-values a literal fails this build, and future drift stays
 * single-sited. Non-Kotlin surfaces (entitlements, `Info.plist`, `build.gradle.kts`) carry their
 * own pinned counts; the BGTask ids are pinned in BOTH Kotlin and `Info.plist`, because drift
 * between the two silently kills that background tier (the OS rejects an unpermitted submit and
 * nothing raises).
 *
 * The pin inventory is the spec's (`openspec/specs/architecture-guards/spec.md`): adding, removing,
 * or re-valuing a pin is a spec delta, deliberately.
 */
class RuntimeIdentityTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * Production Kotlin: every `.kt` under a main source set (`src/<x>Main/` or `src/main/`),
     * excluding the test-only `test/` tree and build output. The walk derives from the repository
     * structure — no hand-maintained module list — so code born in a new top-level root (e.g.
     * `adapter/` at migration step 4) is in scope with zero edits here.
     */
    private fun productionKotlin(): List<File> = repoRoot.walkTopDown()
        .onEnter { dir ->
            dir.name != "build" && !dir.name.startsWith(".") &&
                !(dir.parentFile == repoRoot && dir.name == "test")
        }
        .filter { it.isFile && it.extension == "kt" && it.isInMainSourceSet() }
        .toList()

    private fun File.isInMainSourceSet(): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val srcIdx = segments.lastIndexOf("src")
        if (srcIdx == -1 || srcIdx + 1 >= segments.size) return false
        val sourceSet = segments[srcIdx + 1]
        return sourceSet == "main" || sourceSet.endsWith("Main")
    }

    private fun buildFiles(): List<File> = repoRoot.walkTopDown()
        .onEnter { dir -> dir.name != "build" && !dir.name.startsWith(".") }
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .toList()

    /** Quoted Kotlin string literals: exactly one production occurrence each. */
    private val kotlinLiterals = listOf(
        "group.app.snapsync",
        "discovery.changeToken",
        "rejoin.joinedEventId",
        "app.snapsync.album.map",
        "ledger.db",
        "downloads.db",
        "eventconfig.json",
        "device-manifest",
        "accumulator.json",
        "last-uploaded.json",
        "app.snapsync.upload.heartbeat",
        "app.snapsync.download.backstop",
        "app.snapsync.upload.session",
        "app.snapsync.download.bg",
    )

    /**
     * Keychain entries, pinned as (service, account) PAIRS — the pair is the unit of identity, so a
     * cross-swap of accounts between services fails even though every individual string survives.
     */
    private val keychainPairs = listOf(
        "app.snapsync.deviceid" to "deviceid",
        // The config pair survives as a READ-ONLY seat (KeychainConfigReader): the finale ended
        // the 11a write-through — save/clear are file-only — but the read fallback is the entire
        // installed base's update path (the branch ships as one merge, so at ship time every
        // joined device is pre-11a). The pair dies with the post-ship Stage-2 change that deletes
        // the fallback (capability event-rejoin-reconciliation).
        "app.snapsync.config" to "eventconfig",
        "app.snapsync.attest" to "token",
        "app.snapsync.attest" to "keyid",
        "app.snapsync.album" to "albummap",
    )

    private val bgTaskIds = listOf("app.snapsync.upload.heartbeat", "app.snapsync.download.backstop")

    private val baseNames = listOf("SnapSyncKit", "SnapSyncUploadKit")

    private val entitlementsFiles = listOf(
        "iosApp/iosApp/iosApp.entitlements",
        "iosApp/BackgroundUploadExtension/BackgroundUploadExtension.entitlements",
    )

    private fun occurrences(files: List<File>, needle: String): List<String> = files.flatMap { file ->
        file.readText().lineSequence().withIndex()
            .filter { (_, line) -> needle in line }
            .map { (i, _) -> "${file.toRelativeString(repoRoot)}:${i + 1}" }
            .toList()
    }

    private fun assertExactlyOnce(what: String, found: List<String>) {
        assertTrue(
            found.size == 1,
            "$what must appear exactly once; found ${found.size}:" +
                (if (found.isEmpty()) " (nowhere — a move dropped or re-valued it)"
                 else "\n  ${found.joinToString("\n  ")}") +
                "\nThis literal is runtime identity the installed base depends on. If the change is " +
                "intentional, it is a spec delta to architecture-guards, not a casual edit.",
        )
    }

    @Test
    fun `every runtime-identity literal appears exactly once in production Kotlin`() {
        val sources = productionKotlin()
        assertTrue(sources.isNotEmpty(), "production Kotlin scan resolved zero files — the walk is broken")
        for (literal in kotlinLiterals) {
            assertExactlyOnce("Kotlin literal \"$literal\"", occurrences(sources, "\"$literal\""))
        }
    }

    @Test
    fun `every keychain (service, account) pair appears exactly once`() {
        val sources = productionKotlin()
        assertTrue(sources.isNotEmpty(), "production Kotlin scan resolved zero files — the walk is broken")
        for ((service, account) in keychainPairs) {
            val pair = Regex("""service\s*=\s*"${Regex.escape(service)}",\s*account\s*=\s*"${Regex.escape(account)}"""")
            val found = sources.flatMap { file ->
                pair.findAll(file.readText()).map { file.toRelativeString(repoRoot) }.toList()
            }
            assertExactlyOnce("Keychain pair (service=$service, account=$account)", found)
        }
    }

    @Test
    fun `the App-Group id is pinned in both entitlements files`() {
        for (path in entitlementsFiles) {
            val file = File(repoRoot, path)
            assertTrue(file.isFile, "$path is missing — the entitlements surface moved; fix this pin's path")
            val found = occurrences(listOf(file), "<string>group.app.snapsync</string>")
            assertExactlyOnce("App-Group id in $path", found)
        }
    }

    @Test
    fun `BGTask ids agree between Kotlin and Info plist`() {
        val plist = File(repoRoot, "iosApp/iosApp/Info.plist")
        assertTrue(plist.isFile, "iosApp/iosApp/Info.plist is missing — the plist surface moved; fix this pin's path")
        val sources = productionKotlin()
        for (id in bgTaskIds) {
            // The Kotlin side is already pinned exactly-once above; here the OS-consulted side must
            // carry the identical value, or the submit is rejected and the tier dies silently.
            assertExactlyOnce("BGTask id $id in Info.plist", occurrences(listOf(plist), "<string>$id</string>"))
            assertExactlyOnce("BGTask id $id in Kotlin", occurrences(sources, "\"$id\""))
        }
    }

    @Test
    fun `framework baseNames appear exactly once in build files`() {
        val files = buildFiles()
        assertTrue(files.isNotEmpty(), "build-file scan resolved zero files — the walk is broken")
        for (name in baseNames) {
            assertExactlyOnce("framework baseName \"$name\"", occurrences(files, "baseName = \"$name\""))
        }
    }
}

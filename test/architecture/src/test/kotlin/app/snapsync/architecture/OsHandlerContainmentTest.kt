package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **No OS completion handler is stored in a field** (`docs/patterns.md`, "Entry points"; spec `sync-status`, "OS
 * completion handlers are released only after their work completes").
 *
 * iOS hands the app a completion block on every background wake, and calling it declares *"I am done"*.
 * Failing to call one costs the app its **future** background wakes — uploads and downloads silently stop
 * happening in the background, permanently, with no error anywhere. Two properties prevent that, and a
 * bare field can express neither: the hold must **end** — after the wake's own work, or at the operating
 * system's expiry, never at a clock of the app's own — and a second handover must not **replace**
 * an outstanding handler rather than releasing it.
 *
 * Both are the runtime's now, by construction: an adapter wraps the block it was handed in an immutable
 * `Completion` and delivers it with the event that brought it, and the run that delivery starts answers it exactly
 * once — when its work ends, or after the expiry's grace. So nothing needs to keep a handler in a mutable field, and
 * nothing may: there is no licensed owner, and the rule is a plain ban.
 *
 * **Why the type and not the name.** A previous attempt matched field *names* containing `ompletion` /
 * `nComplete`. `IosUrlSessionUploadPlatform` declared a nullable nullary-`Unit` function `var` named
 * `onBackgroundEventsFinished` — the exact forbidden type, in a directory that guard was scanning — and passed,
 * purely on its name. Matching the type over the whole project is what makes the rule mean anything.
 *
 * **Residue, stated rather than implied away.** This catches *storing*, not *releasing early*: an adapter that
 * invokes its raw handler inline stores nothing and passes here. Not matched either: a handler held in a collection
 * or a property delegate, one behind a `typealias`, and any shape that is not a nullary `Unit`-returning function
 * (a handler taking an argument, e.g. the silent-push fetch handler's `(UIBackgroundFetchResult) -> Void`, would
 * need the rule widened). It reads raw source text, so it also matches the shape inside a **comment**: prose must
 * describe the declaration rather than quote it. That is the same trade [MainLaneContainmentTest] makes, and it errs
 * toward noticing. `val` is deliberately outside the rule: an adapter's `Completion` takes the block as an immutable
 * parameter, which can be neither overwritten nor left unreleased. **If one of these bites, widen the rule** — do not
 * add an exception.
 */
class OsHandlerContainmentTest {

    /**
     * A mutable property whose type is a nullary `Unit`-returning function — the shape a stored OS
     * completion handler takes. Nullable or not, `lateinit` or not, `suspend` or not, so the obvious
     * routes around it (`lateinit var h: () -> Unit`) are closed at no extra cost.
     */
    private val storedHandler =
        Regex("""\b(?:lateinit\s+)?var\s+\w+\s*:\s*\(?\s*(?:suspend\s+)?\(\s*\)\s*->\s*Unit\s*\)?\??""")

    /** The same shape in Swift: `var backgroundCompletion: (() -> Void)?` on the app delegate. */
    private val storedHandlerSwift =
        Regex("""\bvar\s+\w+\s*:\s*\(\s*\(\s*\)\s*->\s*Void\s*\)\s*[?!]""")

    /**
     * Kotlin production source. Two exclusions, and they are not the same one: `/test/` drops the
     * test-only **modules** (`:test:architecture` — this file — `:test:integration`, `:test:rig`), while
     * `Test.kt` drops test classes inside product modules' `commonTest` source sets, which the path filter
     * does not reach.
     */
    private fun productionFiles() = SourceScan.kotlinFiles()
        .filterNot { it.path.contains("/test/") }
        .filterNot { it.path.endsWith("Test.kt") }

    /**
     * The Swift shells, scanned in their own language — because **that is where Apple puts it**. The
     * documented recipe stores the handler on the `UIApplicationDelegate`, so a Swift `var` holding a
     * `(() -> Void)?` is the single most likely reintroduction, and a Kotlin-only rule would never see it.
     * [MainLaneContainmentTest] makes the same point about `DispatchQueue.main`: a gate watching one
     * language misses the shell.
     *
     * Read as plain text: Swift is outside every Kotlin tool's reach.
     */
    private fun swiftShellFiles(): List<File> {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: fail("could not locate the repository root")
        return File(root, "iosApp").walkTopDown().filter { it.isFile && it.extension == "swift" }.toList()
    }

    /**
     * The non-vacuity twin, in two halves — because "found no violations" and "looked at nothing" are
     * different answers and this gate must never confuse them.
     *
     * The first half catches a moved or renamed source tree. The second catches the failure that actually
     * happened last time: a rule that still runs, still scans, and no longer matches the thing it forbids.
     * A regex cannot be trusted to police a shape unless it is shown, in the same run, still recognising
     * that shape — and still ignoring the immutable one that must stay legal.
     */
    @Test
    fun `the gate scans real files and its rule still recognises the shape`() {
        val files = productionFiles()
        assertTrue(
            files.size >= 100,
            "scanned only ${files.size} production files — the source tree moved and this gate proves nothing",
        )
        val swift = swiftShellFiles()
        assertTrue(
            swift.size >= 2,
            "found only ${swift.size} Swift shell files — iosApp/ moved and the Swift half proves nothing",
        )
        assertTrue(
            storedHandlerSwift.containsMatchIn("var backgroundCompletion: (() -> Void)?"),
            "the Swift rule stopped recognising a stored handler",
        )
        assertTrue(
            !storedHandlerSwift.containsMatchIn("let completionHandler: () -> Void"),
            "the Swift rule now rejects a legal declaration",
        )

        val mustMatch = listOf(
            "private var backgroundEventsCompletion: (() -> Unit)? = null",
            "var onBackgroundEventsFinished: (() -> Unit)? = null",
            "lateinit var completion: () -> Unit",
            "var completion: () -> Unit = {}",
        )
        val mustNotMatch = listOf(
            "private val handler: () -> Unit,", // a bare completion's own parameter — immutable, and legal
            "private val onTerminal: () -> Unit,",
            "var onStaged: (suspend (AssetRef, resourceKey: String, stagedPath: String) -> Unit)? = null",
        )
        mustMatch.forEach {
            assertTrue(storedHandler.containsMatchIn(it), "the rule stopped recognising a stored handler: $it")
        }
        mustNotMatch.forEach {
            assertTrue(!storedHandler.containsMatchIn(it), "the rule now rejects a legal declaration: $it")
        }
    }

    @Test
    fun `no production source stores an OS completion handler`() {
        val offenders = productionFiles()
            .flatMap { file -> storedHandler.findAll(file.text).map { file.path to it.value.trim() } } +
            swiftShellFiles()
                .flatMap { file -> storedHandlerSwift.findAll(file.readText()).map { file.path to it.value.trim() } }
        if (offenders.isEmpty()) return
        fail(
            buildString {
                appendLine(
                    "An OS completion handler must not be stored in a field: wrap it in a `Completion` and deliver it",
                )
                appendLine(
                    "with its event — the run answers it once, after its work or at the OS's expiry. A bare field",
                )
                appendLine("does neither, and an unanswered handler costs the app its future background wakes.")
                offenders.forEach { (path, decl) -> appendLine("  $path :: $decl") }
            },
        )
    }
}

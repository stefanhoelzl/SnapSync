package app.snapsync.architecture

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Kotlin source enumeration for the guards that read text** (capability `architecture-guards`).
 *
 * Five guards used to obtain their file list from Konsist. None of them used Konsist: they called
 * `.files`, `.path` and `.text` and then matched with a `Regex`, so a PSI parser was doing the work of
 * `File.walkTopDown()`. That mattered, because the parser was not free — Konsist 0.17.3 last shipped in
 * December 2024 and embeds a Kotlin **2.0.21** compiler while this project builds with **2.4.0**, so five
 * guards were parsing 2.4 source with a two-minor-versions-old front end for no benefit at all.
 *
 * Reading files directly removes that exposure without adding another dependency in its place, and it is
 * what four sibling guards already do with their reasons stated ([TransferSessionBindingTest],
 * [SceneRecordCompletenessTest], [SwiftShellGuardTest], and this file's own callers for their Swift
 * halves). Guards that genuinely need a resolved model would be a different question; there are none.
 *
 * The one property this MUST preserve is reach: `iosMain` is Kotlin/Native source with no JVM bytecode,
 * unreadable by any classpath-based tool, and it is where `SecItem*` and the main-lane forms live. Reading
 * the repository's files reaches it for the same reason Konsist did — it never compiles anything.
 */
internal object SourceScan {

    val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** A scanned file, carrying the two things every caller uses. */
    class Source(val file: File) {
        /** Repo-relative, `/`-separated, and always leading-`/` so `endsWith("/Foo.kt")` is anchored. */
        val path: String = "/" + file.toRelativeString(repoRoot).replace('\\', '/')
        val text: String by lazy { file.readText() }
    }

    /**
     * Every hand-written Kotlin file in the repository.
     *
     * `build/` is excluded because those are other tasks' outputs; guards read hand-written source. The
     * scan fails rather than returning empty, since a guard that scans nothing passes vacuously — the one
     * failure mode these tests may not have.
     */
    fun kotlinFiles(): List<Source> {
        val files = repoRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .map(::Source)
            .toList()
        assertTrue(
            files.isNotEmpty(),
            "the source scan matched no Kotlin files under $repoRoot — the repository layout moved, and " +
                "every guard standing on this scan would pass while reading nothing",
        )
        return files
    }
}

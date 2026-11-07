package app.snapsync.architecture

import java.io.File

/**
 * **What a shipped binary links**, read from the build scripts: the closure of a root's MAIN project dependencies — a
 * test source set's block, a rig-switched block or line (`if (rigEnabled)`), a `testImplementation` and coverage
 * crediting excluded, since none of them reaches a shipped binary. Shared by the gates that ask what a shipped root
 * carries (`MockContainmentTest`, `ExtensionSafetyTest`).
 */
internal object ShippedLinks {
    /** The rig build's switch, as every build file spells it. */
    const val RIG = "rigEnabled"

    private val project = Regex("""project\(\s*"(:[^"]+)"\s*\)""")

    /** A test source set's block header — `commonTest.dependencies`, `jvmTest { … }`, `iosSimulatorArm64Test`. */
    private val testBlock = Regex("""\w+Test\b""")

    fun buildFileOf(module: String): File =
        File(ZoneGates.repoRoot, module.removePrefix(":").replace(':', '/') + "/build.gradle.kts")

    /** Code lines of [file] — comments dropped, so a line that only DESCRIBES a dependency is not one. */
    fun codeLines(file: File): List<String> =
        file.readLines().map { it.substringBefore("//") }.filter { it.isNotBlank() }

    /** Every module the shipped binaries of [roots] link, the roots included. */
    fun closure(roots: List<String>): Set<String> {
        val reached = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val module = queue.removeFirst()
            if (!reached.add(module)) continue
            val file = buildFileOf(module)
            if (!file.isFile) continue
            mainProjectDependencies(file).forEach { queue.addLast(it) }
        }
        return reached
    }

    /** A line that declares a main dependency: not a test configuration, not rig-switched, not coverage crediting. */
    private fun isMainDependency(line: String): Boolean = listOf(
        "testImplementation",
        RIG,
        "kover(",
    ).none { it in line }

    /**
     * The `project(":…")` dependencies [file] declares for MAIN code: not inside a test source set's block or a
     * rig-switched one, not on a `testImplementation`, and not on a rig-switched line. Brace depth tracks the enclosing
     * blocks.
     */
    private fun mainProjectDependencies(file: File): List<String> {
        val stack = ArrayDeque<String>()
        val found = mutableListOf<String>()
        codeLines(file).forEach { line ->
            val excluded = stack.any { header -> testBlock.containsMatchIn(header) || RIG in header }
            if (!excluded && isMainDependency(line)) project.findAll(line).forEach { found += it.groupValues[1] }
            // Open and close the blocks this line opens and closes, in order.
            line.forEachIndexed { i, c ->
                when (c) {
                    '{' -> stack.addLast(line.substring(0, i))
                    '}' -> stack.removeLastOrNull()
                }
            }
        }
        return found
    }
}

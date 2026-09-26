package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **An adapter's constructor takes no function** (`docs/architecture.md`, "Ports and seams").
 *
 * A port implementation is one external system, translated. A function handed to its constructor is a callback some
 * root wired into it — a decision, or a second system, reached from inside an adapter where no mock can see it and no
 * second platform inherits it. What the platform tells the core arrives through `listen` (the composition's handlers);
 * what the core asks of the platform is a port method. Neither needs a function on a constructor, so none is allowed:
 * ANY function type, on the primary constructor or a secondary one, of any class implementing a port interface.
 *
 * WHAT IT READS. Every class in the adapter modules' production AND rig source sets (a rig adapter is an adapter too)
 * — the honest in-memory doubles of `:adapter:generic:fake` included — whose supertypes name an interface
 * `domain/ports` declares. The port set is derived from the ports module, never listed. Heuristic in one respect,
 * stated: a secondary constructor is attributed to the nearest preceding class, which is right for every file today.
 *
 * THE EXEMPTIONS ARE EXACT. Each is an adapter-private bridge whose function IS the operating system's own block — a
 * completion handler the platform handed over, wrapped as a `Completion` so the core can release it once. That is the
 * platform's data, not a callback the composition wired.
 */
class AdapterConstructorTest {

    /** `Class.parameter` → why the function is the platform's own, not a wired callback. */
    private val exempt = mapOf(
        "SessionCompletion.handler" to
            "a background URLSession relaunch's completion handler, the block UIKit handed the app delegate",
        "PushCompletion.handler" to "a silent push's fetch completion handler, the block UIKit handed the app delegate",
    )

    private val ports: Set<String> by lazy {
        SourceScan.kotlinFiles()
            .filter { it.path.startsWith("/domain/ports/src/commonMain/") }
            .flatMap { src -> PORT.findAll(src.text).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    private val adapterSources: List<SourceScan.Source> by lazy {
        SourceScan.kotlinFiles().filter { src -> src.path.startsWith("/adapter/") && SCANNED_SET.containsMatchIn(src.path) }
    }

    /** Every class in [code] whose supertypes name a port interface. */
    private fun portImplementations(code: String): Set<String> = CLASS_HEADER.findAll(code).mapNotNull { m ->
        val afterName = m.range.last + 1
        val supertypes = supertypeClause(code, afterName)
        val names = supertypes.split(',').map { it.trim().substringBefore(' ').substringBefore('(').substringBefore('<') }
        m.groupValues[1].takeIf { names.any { it.substringAfterLast('.') in ports } }
    }.toSet()

    /** The text between a class header's end (after its constructor, if any) and its body or the next declaration. */
    private fun supertypeClause(code: String, from: Int): String {
        var i = from
        // Skip a primary constructor's parameter list, balancing parentheses.
        val open = code.indexOf('(', i).takeIf { it >= 0 && code.substring(i, it).isBlankHeaderTail() }
        if (open != null) {
            var depth = 0
            i = open
            while (i < code.length) {
                if (code[i] == '(') depth++
                if (code[i] == ')') depth--
                i++
                if (depth == 0) break
            }
        }
        val rest = code.substring(i)
        val clause = rest.substringBefore('{').substringBefore("\n\n")
        return clause.trim().removePrefix(":").trim()
    }

    /** Whether the text between a class name and a `(` is only type parameters, a visibility and `constructor`. */
    private fun String.isBlankHeaderTail(): Boolean =
        Regex("""^\s*(?:<[^>{(]*>)?\s*(?:(?:private|internal|public|protected)\s+)?(?:constructor\s*)?$""").matches(this)

    private fun functionParameters(): Map<String, String> = adapterSources.flatMap { src ->
        val code = ZoneGates.stripComments(src.text)
        val implementations = portImplementations(code)
        KotlinDecls.constructorParams(code)
            .filter { it.owner in implementations && "->" in it.type }
            .map { "${it.owner}.${it.name}" to "${src.path}:${it.line} (${it.type})" }
    }.toMap()

    @Test
    fun `no port implementation takes a function in its constructor`() {
        val found = functionParameters()
        val added = found.keys - exempt.keys
        assertTrue(
            added.isEmpty(),
            "a port implementation takes a function in its constructor — what the platform says arrives through " +
                "`listen`, what the core asks is a port method; a function here is a callback wired past both:\n" +
                added.joinToString("\n") { "  $it — ${found[it]}" },
        )
        val gone = exempt.keys - found.keys
        assertTrue(
            gone.isEmpty(),
            "these exemptions no longer match anything — remove them (the list only shrinks):\n" +
                gone.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the scan is real (non-vacuity floor)`() {
        val implementations = adapterSources.sumOf { portImplementations(ZoneGates.stripComments(it.text)).size }
        assertTrue(implementations >= 40, "found only $implementations port implementations in the adapters — the scan is broken")
        assertTrue(adapterSources.any { "/src/rig/" in it.path }, "the rig source sets are in scope")
        val sample = "internal class Probe(private val f: () -> Unit, val n: Int) : Backend, Other {\n}"
        assertEquals(setOf("Probe"), portImplementations(sample), "a class naming a port among its supertypes is one")
        assertEquals(
            listOf("Probe.f"),
            KotlinDecls.constructorParams(sample).filter { "->" in it.type }.map { "${it.owner}.${it.name}" },
        )
    }

    private companion object {
        val PORT = Regex("""^(?:fun\s+)?interface\s+(\w+)""", RegexOption.MULTILINE)

        val CLASS_HEADER = Regex("""\bclass\s+(\w+)""")

        /** An adapter module's production and rig source sets — never its tests. */
        val SCANNED_SET = Regex("""/src/(?:common|jvm|ios|iosArm64|iosSimulatorArm64|apple|native)Main/|/src/rig/""")
    }
}

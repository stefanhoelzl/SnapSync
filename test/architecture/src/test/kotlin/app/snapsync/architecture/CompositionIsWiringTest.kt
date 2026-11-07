package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The composition layer is wiring only** (`docs/architecture.md`, "The composition layer"; `docs/patterns.md`,
 * "Composition is wiring"). What a process does is its layers'; the composition only says which instance goes where,
 * so nothing in it can be wrong in a way a test of the layers would not see:
 *
 *  - **no decision** anywhere in the layer: no `if`, `when`, `for`, `while`, `try`, `?:` — a branch is behaviour, and
 *    behaviour is tested where it lives;
 *  - **no deferral**: no `lazy` — a constructor never touches a port (every store opens on first use), so a graph
 *    built eagerly opens nothing, which `BackgroundCompositionIntegrationTest` proves;
 *  - **graph classes** (`*Graph`) hold only `val`s built by constructor calls: no member function, no lambda — no brace
 *    inside the class body at all;
 *  - **the composing functions are listed**: each module has exactly the top-level functions below — the one ordered
 *    act per process ([PROCESS]'s `startProcess`, the extension's `composeExtension`) — and the one root-side bundle
 *    with a function, `ExtensionDevicePorts.extensionPorts`, which turns a root's lazily-built adapters into ports.
 *
 * Companion factories (`UploadHost.of`), collection builders (`listOfNotNull`) and property reads are wiring: none
 * branches. The scan reads source with comments and string literals blanked.
 */
class CompositionIsWiringTest {

    private fun sources(zone: String): List<Pair<File, String>> =
        ZoneGates.requireZone("composition-is-wiring", zone).map { it to code(it.readText()) }

    /** [source] with comments dropped and every string literal blanked, so prose and messages are never read. */
    private fun code(source: String): String =
        ZoneGates.stripComments(source).replace(Regex(""""(\\.|[^"\\\n])*"""")) { "\"\"" }

    @Test
    fun `the composition layer decides nothing and defers nothing`() {
        val found = ZONES.flatMap { zone ->
            sources(zone).flatMap { (file, code) ->
                code.lines().withIndex().flatMap { (i, line) ->
                    FORBIDDEN.filter { (_, pattern) -> pattern.containsMatchIn(line) }
                        .map { (what, _) -> "${file.toRelativeString(ZoneGates.repoRoot)}:${i + 1} — $what" }
                }
            }
        }
        assertEquals(
            emptyList(),
            found,
            "the composition layer holds behaviour. A branch, a loop, a fallback or a deferral is the layers' — move " +
                "it into a tested class below the composition, and leave here only which instance goes where",
        )
    }

    @Test
    fun `a graph class holds only vals built by constructor calls`() {
        val graphs = ZONES.flatMap { zone ->
            sources(zone).flatMap { (file, code) ->
                GRAPH.findAll(code).map { m -> Triple(file, m.groupValues[1], body(code, m.range.last)) }
            }
        }
        assertTrue(graphs.size >= 3, "found only ${graphs.map { it.second }} graph classes — the scan is broken")
        val offending = graphs.mapNotNull { (file, name, body) ->
            val problems = buildList {
                if (body == null) add("no class body found")
                if (body != null && ('{' in body || '}' in body)) add("a brace in its body (a lambda or a block)")
                if (body != null && Regex("""\bfun\b""").containsMatchIn(body)) add("a member function")
            }
            problems.takeIf { it.isNotEmpty() }?.let { "$name (${file.name}): ${it.joinToString()}" }
        }
        assertEquals(emptyList(), offending, "a graph class holds more than wiring")
    }

    @Test
    fun `each composition module declares exactly its composing functions`() {
        val declared = ZONES.associateWith { zone ->
            sources(zone).flatMap { (_, code) ->
                FUNCTION.findAll(
                    code,
                ).map { m -> listOfNotNull(owner(code, m.range.first), m.groupValues[1]).joinToString(".") }
            }.toSortedSet()
        }
        assertEquals(
            mapOf(
                PROCESS to sortedSetOf("startProcess"),
                EXTENSION to sortedSetOf("composeExtension", "ExtensionDevicePorts.extensionPorts"),
            ),
            declared,
            "a composition module declares a function beyond its one ordered act. Wiring is a graph of vals; a step " +
                "with behaviour belongs in the layers below",
        )
    }

    @Test
    fun `the scan sees what it forbids`() {
        val sample = code(
            """
            class SampleGraph(ports: Ports) {
                val a = if (ports.x) A() else B()
                val b by lazy { B(ports) }
                fun make() = C()
            }
            """.trimIndent(),
        )
        val hits = FORBIDDEN.filter { (_, pattern) -> pattern.containsMatchIn(sample) }.map { it.first }
        assertTrue("an `if`" in hits && "a `lazy`" in hits, "the forbidden-token scan misses: $hits")
        val body = body(sample, GRAPH.find(sample)!!.range.last)!!
        assertTrue('{' in body && Regex("""\bfun\b""").containsMatchIn(body), "the graph-body scan misses: $body")
    }

    /** The text of the class body following the constructor whose `(` is at [open], or `null`. */
    private fun body(code: String, open: Int): String? {
        val closeParams = KotlinDecls.closing(code, open) ?: return null
        val braceAt = code.indexOf('{', closeParams).takeIf { it >= 0 } ?: return null
        if (code.substring(closeParams + 1, braceAt).isNotBlank() && ':' !in code.substring(closeParams + 1, braceAt)) {
            return null
        }
        val closeBody = KotlinDecls.closing(code, braceAt) ?: return null
        return code.substring(braceAt + 1, closeBody)
    }

    /** The class a function at [at] is declared in, or `null` for a top-level function. */
    private fun owner(code: String, at: Int): String? = CLASS.findAll(code).lastOrNull { m ->
        val closeParams = KotlinDecls.closing(code, m.range.last) ?: return@lastOrNull false
        val braceAt = code.indexOf('{', closeParams)
        braceAt in 0 until at && code.substring(closeParams + 1, braceAt).isBlank() &&
            (KotlinDecls.closing(code, braceAt) ?: -1) > at
    }?.groupValues?.get(1)

    private companion object {
        const val PROCESS = "composition/process"
        const val EXTENSION = "composition/extension"
        val ZONES = listOf(PROCESS, EXTENSION)

        val FORBIDDEN = listOf(
            "an `if`" to Regex("""\bif\s*\("""),
            "a `when`" to Regex("""\bwhen\b"""),
            "a `for`" to Regex("""\bfor\s*\("""),
            "a `while`" to Regex("""\bwhile\s*\("""),
            "a `try`" to Regex("""\btry\s*\{"""),
            "an elvis `?:`" to Regex("""\?:"""),
            "a `lazy`" to Regex("""\blazy\b"""),
        )

        val GRAPH = Regex("""\bclass\s+(\w+Graph)\s*\(""")
        val CLASS = Regex("""\bclass\s+(\w+)\s*\(""")
        val FUNCTION = Regex("""\bfun\s+(?:[\w.<>]+\.)?(\w+)\s*\(""")
    }
}

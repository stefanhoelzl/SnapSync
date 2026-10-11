package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **`ports/` holds interfaces, their vocabulary and nothing else** (`docs/architecture.md`, "Ports and seams").
 *
 * A port is what the core uses of one external system: a declaration. Logic sitting beside it — a decision, a helper,
 * an inert implementation, a stateful holder — is code no adapter implements and no mock replaces, living in the one
 * zone whose content should be nothing but the seam. So the zone declares interfaces (plain, `fun` and sealed, with a
 * sealed interface's own cases), the `*Handlers` bundles its event ports are registered with, and the **data
 * vocabulary** the ports speak — value, data and enum classes that carry values and nothing more: no function beyond a
 * `toString`, no nested object. Decisions over that vocabulary are the core's.
 *
 * The vocabulary lives here, beside the ports, so an adapter can depend on the ports alone (phase 2c of the core-layers
 * programme moved the gallery's and the transfers' vocabulary in, out of `model/`, where it sat beside the domain's).
 *
 * There is no allowlist. The port-adjacent code that predated the law left in two phases: 11g1 moved the extension's
 * raw-value mapping to `:adapter:ios:ext-safe` and the extension cycle's never-throw wrapper to `compose/`; the
 * feature → ports cut moved the store interfaces' helpers to `services/`, the pure vocabulary to `model/`, and the
 * inert bindings to `compose/`.
 */
class PortsHoldInterfacesOnlyTest {

    private val sources by lazy {
        SourceScan.kotlinFiles().filter { it.path.startsWith("/domain/ports/src/commonMain/") }
    }

    /**
     * Every declaration in [code] that is neither an interface, a sealed interface's case, a handler bundle, nor a piece
     * of data vocabulary.
     */
    internal fun declarations(code: String): List<String> {
        val out = mutableListOf<String>()
        TOP_LEVEL.findAll(code).forEach { m ->
            val kind = m.groupValues[1]
            val name = m.groupValues[2]
            val handlers = kind == "class" && name.endsWith("Handlers")
            val data = kind in DATA_KINDS && isDataOnly(code, m.range.last + 1)
            if (!handlers && !data) out += name
        }
        // Nested objects: an interface's inert implementation or companion. A sealed interface's own cases are its
        // declaration, not an implementation of anything else.
        NESTED_OBJECT.findAll(code).forEach { m ->
            val owner = Regex("""^(?:sealed\s+|fun\s+)?interface\s+(\w+)""", RegexOption.MULTILINE)
                .findAll(code.substring(0, m.range.first)).lastOrNull()
            val sealedOwner = owner != null && owner.value.startsWith("sealed")
            val name = m.groupValues[1].ifEmpty { "Companion" }
            if (owner != null && !sealedOwner) out += "${owner.groupValues[1]}.$name"
        }
        return out
    }

    /**
     * Whether the class declared before [from] carries data only: no body, or a body holding properties, an `init` that
     * checks them and a `toString` — no other function and no nested object.
     */
    private fun isDataOnly(code: String, from: Int): Boolean {
        val header = code.indexOf('\n', from).let { if (it < 0) code.length else it }
        var open = code.indexOf('{', from)
        // A body opens on the header's own line, or after a multi-line constructor's closing `) {` or `) : X {`.
        var depth = 0
        var i = from
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> depth--
                '\n' -> if (depth == 0 && i >= header) return true
                '{' -> if (depth == 0) {
                    open = i
                    break
                }
            }
            i++
        }
        if (open < 0 || i >= code.length) return true
        var braces = 0
        var end = open
        while (end < code.length) {
            if (code[end] == '{') braces++
            if (code[end] == '}' && --braces == 0) break
            end++
        }
        val body = code.substring(open + 1, end)
        val functions = FUNCTION.findAll(body).map { it.groupValues[1] }.filterNot { it == "toString" }.toList()
        return functions.isEmpty() && !Regex("""\bobject\b""").containsMatchIn(body)
    }

    @Test
    fun `ports declares interfaces and handler bundles only`() {
        val found = sources.flatMap { src -> declarations(ZoneGates.stripComments(src.text)).map { it to src.path } }
        assertTrue(
            found.isEmpty(),
            "`ports/` declares something other than an interface, a `*Handlers` bundle or data vocabulary — a decision " +
                "or helper belongs in `services/` or `model/`, an implementation in an adapter:\n" +
                found.joinToString("\n") { (name, path) -> "  $name — $path" },
        )
    }

    @Test
    fun `the scan is real (non-vacuity floor)`() {
        // Lowered from 40 when the feature → ports cut took the store interfaces out (31 files remain).
        assertTrue(sources.size >= 25, "scanned only ${sources.size} files in domain/ports — the scope is broken")
        val sample = """
            interface Port {
                fun a()
                object None : Port { override fun a() = Unit }
            }
            sealed interface Answer {
                data object Missing : Answer
            }
            class PortHandlers(val onX: () -> Unit)
            class Helper {
                fun help() = 1
            }
            fun helper() = 1
            data class Value(val a: Int)
            class Range(
                val from: Long?,
                val until: Long?,
            ) {
                override fun toString(): String = "Range"
            }
            enum class Kind { A, B }
        """.trimIndent()
        assertTrue(
            declarations(sample).toSet() == setOf("Helper", "helper", "Port.None"),
            "the scan reads a helper, a nested implementation and nothing else — data passes: ${declarations(sample)}",
        )
    }

    private companion object {
        val TOP_LEVEL = Regex(
            """^(?:@JvmInline\s+)?(?:public |internal |private )?(?:inline |suspend )*""" +
                """(data class|enum class|value class|sealed class|abstract class|open class|class|object|fun(?!\s+interface)|val|var|""" +
                """typealias|const val)\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)""",
            RegexOption.MULTILINE,
        )

        /** The class kinds that may be data vocabulary, when their body holds data only. */
        val DATA_KINDS = setOf("class", "data class", "enum class", "value class")

        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)""")

        val NESTED_OBJECT = Regex(
            """^\s+(?:data |private |internal )?(?:companion )?object\s*(\w*)""",
            RegexOption.MULTILINE,
        )
    }
}

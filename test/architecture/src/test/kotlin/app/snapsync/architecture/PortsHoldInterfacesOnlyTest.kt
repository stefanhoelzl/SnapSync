package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **`ports/` holds interfaces only** (`docs/architecture.md`, "Ports and seams").
 *
 * A port is what the core uses of one external system: a declaration. Logic sitting beside it — a decision, a helper,
 * an inert implementation, a stateful holder — is code no adapter implements and no mock replaces, living in the one
 * zone whose content should be nothing but the seam. So the zone declares interfaces (plain, `fun` and sealed, with a
 * sealed interface's own cases) and the `*Handlers` bundles its event ports are registered with — and nothing else,
 * top-level or nested.
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

    /** Every declaration in [code] that is neither an interface, a sealed interface's case, nor a handler bundle. */
    internal fun declarations(code: String): List<String> {
        val out = mutableListOf<String>()
        TOP_LEVEL.findAll(code).forEach { m ->
            val kind = m.groupValues[1]
            val name = m.groupValues[2]
            val handlers = kind == "class" && name.endsWith("Handlers")
            if (!handlers) out += name
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

    @Test
    fun `ports declares interfaces and handler bundles only`() {
        val found = sources.flatMap { src -> declarations(ZoneGates.stripComments(src.text)).map { it to src.path } }
        assertTrue(
            found.isEmpty(),
            "`ports/` declares something other than an interface or a `*Handlers` bundle — a decision or helper " +
                "belongs in `services/` or `model/`, an implementation in an adapter:\n" +
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
            class Helper
            fun helper() = 1
        """.trimIndent()
        assertTrue(
            declarations(sample).toSet() == setOf("Helper", "helper", "Port.None"),
            "the scan reads a helper, a nested implementation and nothing else: ${declarations(sample)}",
        )
    }

    private companion object {
        val TOP_LEVEL = Regex(
            """^(?:public |internal |private )?(?:inline |suspend )*""" +
                """(data class|enum class|sealed class|abstract class|open class|class|object|fun(?!\s+interface)|val|var|""" +
                """typealias|const val)\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)""",
            RegexOption.MULTILINE,
        )

        val NESTED_OBJECT = Regex("""^\s+(?:data |private |internal )?(?:companion )?object\s*(\w*)""", RegexOption.MULTILINE)
    }
}

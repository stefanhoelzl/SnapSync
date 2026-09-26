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
 * THE ALLOWLIST IS EXACT, AND SHRINKS. What is left is the port-adjacent code that predates the law, each named with
 * the phase that re-homes it; an entry that no longer matches fails too, so the phase that moves a declaration removes
 * its line here. 11g1 moved the extension's raw-value mapping to `:adapter:ios:ext-safe` and the extension cycle's
 * never-throw wrapper to `compose/`.
 */
class PortsHoldInterfacesOnlyTest {

    /** `Declaration` (nested: `Owner.Declaration`) → the phase that re-homes it. */
    private val remaining = mapOf(
        "CONFIG_FILE_FOREIGN_STATUS" to FEATURE_CUT,
        "CONFIG_FILE_UNUSABLE_STATUS" to FEATURE_CUT,
        "configReadViaFile" to FEATURE_CUT,
        "configAfterReload" to FEATURE_CUT,
        "membershipAfterReload" to FEATURE_CUT,
        "PlatformUploadJob" to FEATURE_CUT,
        "SecureStoreUnavailable" to FEATURE_CUT,
        "DeviceIdentityAbsent" to FEATURE_CUT,
        "PushTokenSource" to FEATURE_CUT,
        "Discovery" to FEATURE_CUT,
        "DeviceLogSource.Companion" to FEATURE_CUT,
        "StagedBytes.Companion" to FEATURE_CUT,
    )

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
    fun `ports declares interfaces and handler bundles only, beyond what the later phases re-home`() {
        val found = sources.flatMap { src -> declarations(ZoneGates.stripComments(src.text)).map { it to src.path } }
            .toMap()
        val added = found.keys - remaining.keys
        assertTrue(
            added.isEmpty(),
            "`ports/` declares something other than an interface or a `*Handlers` bundle — a decision or helper " +
                "belongs in `services/` or `model/`, an implementation in an adapter:\n" +
                added.joinToString("\n") { "  $it — ${found[it]}" },
        )
        val gone = remaining.keys - found.keys
        assertTrue(
            gone.isEmpty(),
            "these allowlisted declarations no longer exist in `ports/` — remove them from `remaining` (the list " +
                "only shrinks):\n" + gone.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the scan is real (non-vacuity floor)`() {
        assertTrue(sources.size >= 40, "scanned only ${sources.size} files in domain/ports — the scope is broken")
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
        const val FEATURE_CUT = "the feature → ports cut (after 11i), which re-homes the port-adjacent code"

        val TOP_LEVEL = Regex(
            """^(?:public |internal |private )?(?:inline |suspend )*""" +
                """(data class|enum class|sealed class|abstract class|open class|class|object|fun(?!\s+interface)|val|var|""" +
                """typealias|const val)\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)""",
            RegexOption.MULTILINE,
        )

        val NESTED_OBJECT = Regex("""^\s+(?:data |private |internal )?(?:companion )?object\s*(\w*)""", RegexOption.MULTILINE)
    }
}

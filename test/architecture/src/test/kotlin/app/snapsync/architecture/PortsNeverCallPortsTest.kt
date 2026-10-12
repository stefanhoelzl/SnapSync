package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Ports never call ports** (`docs/architecture.md`, "Ports never call ports; only services compose them").
 *
 * A port implementation is one external system, translated. One that holds another port reaches a second external
 * system through the first, and the decision about how the two combine hides inside an adapter where no mock can see
 * it and no second platform inherits it. So: no adapter's constructor takes a port. Combining ports is a service's
 * job (`:domain:services`), or a feature's.
 *
 * WHAT IT READS. Every constructor parameter of every class in the adapter modules' production source sets, typed as
 * an interface `domain/ports` declares. The port set is derived from the ports module, never listed, so a new port is
 * in scope the day it is written. The rig-gated `src/rig` source sets are out of scope by the law's own exception: a
 * rig decorator forwards to the adapter it wraps.
 *
 * WHAT IT DOES NOT SEE. A port reached any other way than through a constructor — a global, a parameter of a method.
 * None exists today; this reads a declaration, not a call graph.
 *
 * THE ALLOWLIST IS EXACT, AND SHRINKS. The law became true of the backend and the storage services in phases 11b and
 * 11c; the entries left are the port-to-port holdings the later phases remove, each named with the phase that owns
 * it. An entry that no longer matches fails too, so the phase that removes a holding removes its line here.
 */
class PortsNeverCallPortsTest {

    /** `Class.parameter` → the phase that removes it. */
    private val remaining = mapOf(
        "SimulatorSecureStore.keychain" to "unowned: the simulator's secure store routing the device-id slot to a file",
        "SimulatorSecureStore.files" to "unowned: the same",
    )

    /**
     * The interfaces that extend `Port`, directly or through another port. A handle or value a port hands over (a
     * `GalleryResourceHandle` a job record keeps) is an interface too, and holding one reaches no second system.
     */
    private val ports: Set<String> by lazy {
        val supertypes = SourceScan.kotlinFiles()
            .filter { it.path.startsWith("/domain/ports/src/commonMain/") }
            .flatMap { src -> INTERFACE.findAll(src.text).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .associate { (name, supers) -> name to SUPERTYPE.findAll(supers).map { it.value }.toSet() }
        generateSequence(setOf("Port")) { known ->
            known + supertypes.filterValues { it.any(known::contains) }.keys
        }.zipWithNext().first { (a, b) -> a == b }.first - "Port"
    }

    private val adapterSources: List<SourceScan.Source> by lazy {
        SourceScan.kotlinFiles().filter { src ->
            src.path.startsWith("/adapter/") && PRODUCTION_SET.containsMatchIn(src.path)
        }
    }

    private fun holdings(): Map<String, String> = adapterSources.flatMap { src ->
        KotlinDecls.constructorParams(ZoneGates.stripComments(src.text))
            // A type written fully qualified into the standard library (`kotlin.time.Clock`) is not the port of the
            // same simple name.
            .filter { !it.type.startsWith("kotlin.") }
            .filter { it.type.removeSuffix("?").substringAfterLast('.').substringBefore('<') in ports }
            .map { "${it.owner}.${it.name}" to "${src.path}:${it.line} (${it.type})" }
    }.toMap()

    @Test
    fun `no adapter holds a port beyond the ones the later phases remove`() {
        val found = holdings()
        val added = found.keys - remaining.keys
        assertTrue(
            added.isEmpty(),
            "an adapter takes a port in its constructor — ports never call ports; compose them in a service " +
                "(`:domain:services`) or a feature instead:\n" + added.joinToString("\n") { "  $it — ${found[it]}" },
        )
        val gone = remaining.keys - found.keys
        assertTrue(
            gone.isEmpty(),
            "these allowlisted holdings no longer exist — remove them from `remaining` (the list only shrinks):\n" +
                gone.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the scan is real (non-vacuity floor)`() {
        // Lowered from 40 when the feature → ports cut took the store interfaces out (36 remain).
        assertTrue(ports.size >= 30, "parsed only ${ports.size} port interfaces from domain/ports — the scan is broken")
        assertTrue(
            "HttpTransport" in ports && "DeviceIntegrity" in ports,
            "the ports this law was written for are in scope",
        )
        assertTrue(adapterSources.size >= 50, "scanned only ${adapterSources.size} adapter files — the scope is broken")
        // A holding in a sample reads as one; a port's own interface declaration does not.
        val sample = "class Probe(private val http: HttpTransport, val name: String) : HttpTransport"
        assertEquals(
            listOf("Probe.http"),
            KotlinDecls.constructorParams(sample).filter { it.type in ports }.map { "${it.owner}.${it.name}" },
        )
        assertTrue(File(SourceScan.repoRoot, "domain/ports/src/commonMain").isDirectory)
    }

    private companion object {
        /** An interface declaration: its name, and what follows its colon up to the body or the line's end. */
        val INTERFACE = Regex(
            """^(?:fun\s+)?interface\s+(\w+)(?:<[^>]*>)?\s*(?::\s*([^{\n]*))?""",
            RegexOption.MULTILINE,
        )

        /** A supertype's simple name. */
        val SUPERTYPE = Regex("""\b[A-Z]\w*""")

        /** The production source sets of a Kotlin Multiplatform adapter module — never tests, never the rig set. */
        val PRODUCTION_SET = Regex("""/src/(?:common|jvm|ios|iosArm64|iosSimulatorArm64|apple|native)Main/""")
    }
}

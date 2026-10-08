package app.snapsync.architecture

import app.snapsync.compose.AppDevicePorts
import app.snapsync.compose.AppPorts
import app.snapsync.compose.ExtensionDevicePorts
import app.snapsync.compose.ExtensionPorts
import app.snapsync.compose.ProcessPorts
import app.snapsync.ports.Port
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **A composition bundle holds ports and nothing else** (`docs/architecture.md`, "One shared composition"; law "Ports
 * are the I/O boundary named for the need").
 *
 * The roots hand their compositions ports — [AppPorts], [ExtensionPorts], [ProcessPorts], [AppDevicePorts],
 * [ExtensionDevicePorts] — and the
 * compositions build every service, read every build constant from the `BuildInfo` port, and leave the main thread to
 * the platform-UI adapters. A service, a constant, a lambda or a dispatcher in a bundle is something a root decided for
 * the core, and the drift this gate exists to end: `AppPorts` once carried a dozen root-built services, the build's
 * constants, two coordination lambdas and the main lane beside its ports, so each root assembled a slightly different
 * graph and nothing said so.
 *
 * Read by reflection over the compiled bundles, so what it checks is what the compiler built: each constructor
 * parameter is a [Port], a `List` of ports (a process's log sinks), a `Lazy` port (the device bundles' first-use adapters),
 * or one of these bundles nested ([ProcessPorts] inside the app's and the extension's).
 *
 * And the other direction: every interface in `:domain:ports` extends [Port], so "a port" is a checkable fact rather
 * than a directory. The few that are not ports are listed in [notPorts], with their reason.
 */
class PortBundleTest {

    private val bundles = listOf(
        AppPorts::class.java,
        ExtensionPorts::class.java,
        ProcessPorts::class.java,
        AppDevicePorts::class.java,
        ExtensionDevicePorts::class.java,
    )

    /** Interfaces in `:domain:ports` that are NOT ports — each a handle or a value a port hands over. */
    private val notPorts = mapOf(
        "Completion" to "the OS's completion handler for one delivery, handed to the core with it — a handle, not a seam",
        "ExpiringCompletion" to "a wake's completion handler, which the OS may end early — a handle, not a seam",
        "BackgroundTimeHold" to "one hold on the process's background time, answered by the BackgroundTime port",
        "LibraryChangeToken" to "an opaque value the gallery answers and is later handed back — a value, not a seam",
        "DbOpen" to "the result of opening a database — a sealed answer the Databases port returns",
    )

    @Test
    fun `every composition bundle field is a port`() {
        val offenders = bundles.flatMap { bundle ->
            primaryConstructor(bundle).genericParameterTypes.withIndex()
                .filterNot { (_, type) -> isPortShaped(type) }
                .map { (index, type) -> "  ${bundle.simpleName} parameter #${index + 1}: ${type.typeName}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "a composition bundle carries something that is not a port. Build a service inside the composition " +
                "(`AppServices`, the extension's services), read a build constant from `BuildInfo`, let the adapter " +
                "that needs the main thread reach it itself — or, if it really is a seam, declare it in :domain:ports " +
                "extending `Port`.\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the gate parsed every bundle (non-vacuity floor)`() {
        val floors = mapOf(
            AppPorts::class.java to 20,
            ExtensionPorts::class.java to 9,
            ProcessPorts::class.java to 7,
            AppDevicePorts::class.java to 20,
            ExtensionDevicePorts::class.java to 10,
        )
        floors.forEach { (bundle, floor) ->
            val count = primaryConstructor(bundle).parameterCount
            assertTrue(
                count >= floor,
                "port-bundle gate: ${bundle.simpleName} read with only $count parameters (expected $floor+)",
            )
        }
    }

    @Test
    fun `every interface in the ports zone extends Port`() {
        val declared = portInterfaces()
        assertTrue(
            declared.size >= 30,
            "port-bundle gate: found only ${declared.size} interfaces in :domain:ports — the scan is broken",
        )
        val offenders = (declared - notPorts.keys).filterNot { Port::class.java.isAssignableFrom(portsClass(it)) }
        assertTrue(
            offenders.isEmpty(),
            "an interface in :domain:ports does not extend `Port`: ${offenders.sorted()}. A port declares it; a handle " +
                "or a value a port hands over is listed in `notPorts` with its reason.",
        )
        val stale = notPorts.keys - declared
        assertTrue(stale.isEmpty(), "port-bundle gate: `notPorts` lists interfaces that no longer exist: $stale")
        val misfiled = notPorts.keys.filter { Port::class.java.isAssignableFrom(portsClass(it)) }
        assertTrue(misfiled.isEmpty(), "port-bundle gate: $misfiled extend `Port` but are listed as not ports")
    }

    @Test
    fun `the shape check refuses what is not a port`() {
        class Sample(
            val lane: kotlin.coroutines.CoroutineContext,
            val name: String,
            val call: () -> Unit,
            val many: List<String>,
        )
        val types = primaryConstructor(Sample::class.java).genericParameterTypes
        assertTrue(types.none(::isPortShaped), "the shape check admitted a non-port: ${types.map { it.typeName }}")
    }

    private fun primaryConstructor(bundle: Class<*>) =
        bundle.declaredConstructors.filterNot { it.isSynthetic }.maxBy { it.parameterCount }

    private fun isPortShaped(type: Type): Boolean = when (type) {
        is Class<*> -> Port::class.java.isAssignableFrom(type) || type in bundles
        is ParameterizedType -> type.rawType in setOf(List::class.java, Lazy::class.java) &&
            type.actualTypeArguments.single().let { isPortShaped(unwrap(it)) }
        else -> false
    }

    private fun unwrap(type: Type): Type = if (type is WildcardType) type.upperBounds.single() else type

    /** The top-level interfaces declared in `:domain:ports`, by simple name, read from its source. */
    private fun portInterfaces(): Set<String> {
        val dir = File(SourceScan.repoRoot, "domain/ports/src/commonMain/kotlin/app/snapsync/ports")
        assertTrue(dir.isDirectory, "port-bundle gate: $dir is gone — re-point the scan")
        return dir.walk().filter { it.extension == "kt" }.flatMap { file ->
            Regex(
                """^(?:sealed |fun )?interface\s+(\w+)""",
                RegexOption.MULTILINE,
            ).findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()
    }

    private fun portsClass(name: String): Class<*> = Class.forName("app.snapsync.ports.$name")
}

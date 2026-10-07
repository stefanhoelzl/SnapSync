package app.snapsync.architecture

import app.snapsync.ports.Listenable
import app.snapsync.ports.Port
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The port grid: every answer an adapter can give, as cells** (`docs/testing.md`, "The port grid"). REPORT ONLY —
 * it fails nothing but its own scan breaking.
 *
 * The contract-coverage gate ([ContractCoverageTest]) asks whether every clause runs against something real. This asks
 * the other question: of everything a port's adapter can answer, which cells exist at all. A cell is one of
 *  - `Port.member → Variant` — a member the adapter implements × each variant of what it returns;
 *  - `Port.handlers.field(Arg, …)` — for an event port ([Listenable]), a handler the adapter CALLS × each variant of
 *    every argument it passes (crossed). What the handler returns is the core's answer, not the adapter's, so out;
 *  - `Port.member.param(Arg, …)` — the same for a callback the core hands a member (`BackgroundTime.begin`'s expiry);
 *  - `Port.Handle.member → Variant` — a handle a port hands over ([PortBundleTest]'s not-ports `Completion`,
 *    `BackgroundTimeHold`, `LibraryChangeToken`), counted under each port that hands it out, since each is a
 *    different adapter's.
 *
 * Variants: a sealed type → each leaf subtype (`Reply.Ok`; the OUTER type of a generic only); an enum → each entry;
 * `Boolean` → `true`/`false`; a `Flow<T>` → T's; anything else → one `returns` cell, or its type name as an argument.
 * A nullable type adds `null`. Throws are not cells: errors are reduced into values (`docs/architecture.md`). A member
 * the interface itself implements (a default body) is not the adapter's answer and is not a cell; a member inherited
 * from another port is counted under the port declaring it, except a generic base's ([Listenable.listen]), counted
 * under every port that binds it.
 *
 * Read by REFLECTION over the compiled `:domain:ports`, not by text: a regex over the source counts the generic
 * `Reply<T>` as having no subtypes at all. Ports are found as [PortBundleTest] finds them — every interface declared
 * in the zone, filtered to those extending [Port] — so a new port is in the grid by construction.
 *
 * Writes `build/reports/port-grid/port-grid.txt` (sorted, one cell per line — the denominator) and
 * `port-grid-estimate.txt` (each cell marked `~` where a PORT contract's source mentions its member and variant —
 * a size estimate of the missing clauses, NOT coverage). Contracts whose subject is a service are left out of the
 * estimate and listed at its foot: they are not port contracts.
 */
class PortGridTest {

    private class Cell(val port: String, val text: String, val member: String, val variants: List<String>)

    private val ports: List<KClass<*>> = portInterfaces()
        .map { Class.forName("$PORTS_PACKAGE.$it").kotlin }
        .filter { Port::class.isSuperclassOf(it) && it != Port::class && it.typeParameters.isEmpty() }
        .sortedBy { it.simpleName }

    private val cells: List<Cell> = ports.flatMap(::cellsOf).sortedBy { it.text }

    @Test
    fun `the port grid is written`() {
        val out = File("build/reports/port-grid").apply { mkdirs() }
        File(out, "port-grid.txt").writeText(cells.joinToString("\n", postfix = "\n") { it.text })

        val contracts = contractSources()
        val (service, port) = contracts.partition { it.second }
        val portText = port.joinToString("\n") { it.first.text }
        val looked = cells.associateWith { looksCovered(it, portText) }
        val byPort = cells.groupBy { it.port }.toSortedMap()
        val summary = byPort.map { (p, cs) ->
            "%-24s %4d cells  %4d ~".format(
                p,
                cs.size,
                cs.count { looked.getValue(it) },
            )
        }
        File(out, "port-grid-estimate.txt").writeText(
            buildString {
                appendLine(
                    "# ${cells.size} cells over ${byPort.size} ports; ${looked.values.count { it }} mentioned by a port contract's source (~).",
                )
                appendLine("# An ESTIMATE of the gap, not coverage: a mention is not a clause asserting the cell.")
                summary.forEach { appendLine("# $it") }
                appendLine()
                cells.forEach { appendLine((if (looked.getValue(it)) "~ " else "  ") + it.text) }
                appendLine()
                appendLine("# Service-subject contracts, outside the estimate (not port contracts):")
                service.map { it.first.path }.sorted().forEach { appendLine("#   $it") }
            },
        )
        println("port grid: ${cells.size} cells over ${byPort.size} ports → ${out.absolutePath}")
        summary.forEach { println("  $it") }
    }

    // ---- non-vacuity: the derivation still reads what it was built against ------------------------------------

    @Test
    fun `the grid finds the ports`() {
        assertTrue(
            ports.size >= 30,
            "port grid: only ${ports.size} ports found — the reflection over :domain:ports broke",
        )
    }

    @Test
    fun `the grid expands a generic sealed return, an enum, a nullable and a handle`() {
        val texts = cells.map { it.text }.toSet()
        val canaries = listOf(
            "Backend.challenge → Reply.Unreachable",
            "SecureStore.read → SecureStoreRead.Unavailable",
            "LibraryChangeTokenRead.changeToken → null",
            "Wake.Completion.complete → returns",
        )
        val missing = canaries - texts
        assertTrue(missing.isEmpty(), "port grid: expected cells are missing, so the variant expansion broke: $missing")
    }

    @Test
    fun `every event port has handler cells`() {
        val bare = ports.filter { Listenable::class.isSuperclassOf(it) }
            .map { it.simpleName!! }
            .filter { p -> cells.none { it.port == p && ".handlers." in it.text } }
        assertTrue(bare.isEmpty(), "port grid: event ports with no handler cell — the handler bundle read broke: $bare")
    }

    // ---- derivation -----------------------------------------------------------------------------------------

    private fun cellsOf(port: KClass<*>): List<Cell> {
        val name = port.simpleName!!
        val handles = mutableSetOf<KClass<*>>()
        val own = ownedMembers(port).flatMap { memberCells(name, name, it, handles) }
        val handlers = handlerBundle(port)?.let { bundle ->
            bundle.declaredMemberProperties.sortedBy { it.name }.flatMap { f ->
                callbackCells(name, "$name.handlers.${f.name}", f.name, f.returnType, handles)
            }
        }.orEmpty()
        val handed = handles.sortedBy { it.simpleName }.flatMap { h ->
            ownedMembers(h).flatMap { memberCells(name, "$name.${h.simpleName}", it, mutableSetOf()) }
        }
        return own + handlers + handed
    }

    /** The abstract members [type] answers for itself: declared here, or inherited from a non-port or generic port base. */
    private fun ownedMembers(type: KClass<*>): List<KCallable<*>> {
        val bases = type.allSupertypes.mapNotNull { it.classifier as? KClass<*> }
            .filter { it != Any::class && it != Port::class && !(Port::class.isSuperclassOf(it) && it.typeParameters.isEmpty()) }
        return (listOf(type) + bases).flatMap { it.declaredMembers }.filter { it.isAbstract }.sortedBy { it.name }
    }

    private fun memberCells(port: String, owner: String, member: KCallable<*>, handles: MutableSet<KClass<*>>): List<Cell> {
        noteHandle(member.returnType, handles)
        val returns = variants(member.returnType, plain = RETURNS).map {
            Cell(port, "$owner.${member.name} → $it", member.name, listOf(it))
        }
        val callbacks = member.parameters.filter { it.kind == KParameter.Kind.VALUE && isFunction(it.type) }
            .flatMap { callbackCells(port, "$owner.${member.name}.${it.name}", member.name, it.type, handles) }
        return returns + callbacks
    }

    /** One cell per crossing of the variants of every argument the adapter passes a callback of [type]. */
    private fun callbackCells(
        port: String,
        prefix: String,
        member: String,
        type: KType,
        handles: MutableSet<KClass<*>>,
    ): List<Cell> {
        val args = type.arguments.dropLast(1).mapNotNull { it.type }
            .filterNot { (it.classifier as? KClass<*>) == Continuation::class }
        args.forEach { noteHandle(it, handles) }
        val crossed = args.fold(listOf(emptyList<String>())) { acc, arg ->
            val vs = variants(arg, plain = (arg.classifier as? KClass<*>)?.simpleName ?: arg.toString())
            acc.flatMap { done -> vs.map { done + it } }
        }
        return crossed.map { Cell(port, "$prefix(${it.joinToString(", ")})", member.substringAfterLast('.'), it) }
    }

    private fun variants(type: KType, plain: String): List<String> {
        val k = type.classifier as? KClass<*>
        val base = when {
            k == null -> listOf(plain)
            k == Boolean::class -> listOf("true", "false")
            k.isSubclassOf(Flow::class) -> variants(type.arguments.single().type!!, plain)
            k.java.isEnum -> k.java.enumConstants.map { "${k.simpleName}.${(it as Enum<*>).name}" }
            k.isSealed -> leaves(k).map(::relativeName)
            else -> listOf(plain)
        }
        return if (type.isMarkedNullable) base + "null" else base
    }

    private fun leaves(k: KClass<*>): List<KClass<*>> =
        k.sealedSubclasses.flatMap { if (it.isSealed) leaves(it) else listOf(it) }.sortedBy { it.qualifiedName }

    private fun relativeName(k: KClass<*>): String = k.qualifiedName!!.removePrefix(k.java.`package`.name + ".")

    private fun isFunction(type: KType): Boolean =
        (type.classifier as? KClass<*>)?.let { Function::class.isSuperclassOf(it) } == true

    /** A non-port, non-sealed interface of the ports zone that a port hands over: a handle, whose members are cells too. */
    private fun noteHandle(type: KType, handles: MutableSet<KClass<*>>) {
        val k = type.classifier as? KClass<*> ?: return
        val inZone = k.java.isInterface && k.java.packageName == PORTS_PACKAGE
        if (inZone && !Port::class.isSuperclassOf(k) && !k.isSealed) handles += k
    }

    private fun handlerBundle(port: KClass<*>): KClass<*>? =
        port.allSupertypes.firstOrNull {
            it.classifier == Listenable::class
        }?.arguments?.single()?.type?.classifier as? KClass<*>

    // ---- estimate -------------------------------------------------------------------------------------------

    private fun looksCovered(cell: Cell, text: String): Boolean {
        if (!Regex("""\b${Regex.escape(cell.member)}\b""").containsMatchIn(text)) return false
        return cell.variants.all { v ->
            v == RETURNS || v == "true" || v == "false" || v == "null" || v.firstOrNull()?.isUpperCase() != true ||
                v.substringAfterLast('.').let { leaf -> Regex("""\b${Regex.escape(leaf)}\b""").containsMatchIn(text) }
        }
    }

    /** Every contract source, paired with whether its subject is a service (`Contract<S, X>`/`ClauseList<S, X>`, X from services). */
    private fun contractSources(): List<Pair<SourceScan.Source, Boolean>> {
        val dir = File(SourceScan.repoRoot, CONTRACTS_DIR)
        assertTrue(dir.isDirectory, "port grid: $dir is gone — re-point the estimate")
        return dir.walk().filter { it.extension == "kt" }.map(SourceScan::Source).map { src ->
            val subjects = SUBJECT.findAll(src.text).map { it.groupValues[1] }.toList()
            val services = SERVICE_IMPORT.findAll(src.text).map { it.groupValues[1] }.toSet()
            src to (subjects.isNotEmpty() && subjects.all { it in services })
        }.toList()
    }

    /** The top-level interfaces declared in `:domain:ports`, by simple name — the same read [PortBundleTest] makes. */
    private fun portInterfaces(): Set<String> {
        val dir = File(SourceScan.repoRoot, "domain/ports/src/commonMain/kotlin/app/snapsync/ports")
        assertTrue(dir.isDirectory, "port grid: $dir is gone — re-point the scan")
        return dir.walk().filter { it.extension == "kt" }.flatMap { file ->
            Regex("""^(?:sealed |fun )?interface\s+(\w+)""", RegexOption.MULTILINE).findAll(file.readText()).map {
                it.groupValues[1]
            }
        }.toSet()
    }

    private companion object {
        const val PORTS_PACKAGE = "app.snapsync.ports"
        const val RETURNS = "returns"
        const val CONTRACTS_DIR = "test/contracts/src/commonMain/kotlin/app/snapsync/contracts"
        val SUBJECT = Regex("""(?:Contract|ClauseList)<\w+,\s*(\w+)""")
        val SERVICE_IMPORT = Regex("""^import app\.snapsync\.services\.[\w.]*\.(\w+)$""", RegexOption.MULTILINE)
    }
}

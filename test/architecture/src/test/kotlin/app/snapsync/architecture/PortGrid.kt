package app.snapsync.architecture

import app.snapsync.ports.Listenable
import app.snapsync.ports.Port
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertTrue

/**
 * **The port grid's derivation** (`docs/testing.md`, "The port grid"): every cell an adapter can answer, read by
 * reflection over the compiled `:domain:ports`. [PortGridTest] reports it; [ClauseCoversTest] holds every clause's
 * declared cells to it. One derivation, so the two can never disagree about what a cell's text is.
 */
internal object PortGrid {

    class Cell(val port: String, val text: String, val member: String, val variants: List<String>)

    val ports: List<KClass<*>> by lazy {
        portInterfaces()
            .map { Class.forName("$PORTS_PACKAGE.$it").kotlin }
            .filter { Port::class.isSuperclassOf(it) && it != Port::class && it.typeParameters.isEmpty() }
            .sortedBy { it.simpleName }
    }

    val cells: List<Cell> by lazy { ports.flatMap(::cellsOf).sortedBy { it.text } }

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
        val returns = (variants(member.returnType, plain = RETURNS) + listOfNotNull(THROWS.takeIf { declaresThrows(member) }))
            .map { Cell(port, "$owner.${member.name} → $it", member.name, listOf(it)) }
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

    /**
     * Whether [member] declares a throw as one of its answers (`@Throws`). Kotlin's `@Throws` is source-retained and
     * compiled into the method's `throws` clause, so it is read off the Java method.
     */
    private fun declaresThrows(member: KCallable<*>): Boolean =
        (member as? KFunction<*>)?.javaMethod?.exceptionTypes?.isNotEmpty() == true

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

    private const val PORTS_PACKAGE = "app.snapsync.ports"
    const val RETURNS = "returns"
    const val THROWS = "throws"
}

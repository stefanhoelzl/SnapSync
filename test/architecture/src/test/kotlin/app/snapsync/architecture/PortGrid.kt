package app.snapsync.architecture

import app.snapsync.compose.AppPorts
import app.snapsync.ports.Listenable
import app.snapsync.ports.Port
import app.snapsync.wire.api.Reply
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertTrue

/**
 * **The port grid's derivation** (`docs/testing.md`, "The port grid"): every cell an adapter can answer, read by
 * reflection over the compiled `:domain:ports` — and every outcome the backend can answer, read off the generated
 * replies of `:domain:wire` ([wireCells]). [PortGridTest] reports it; [ClauseCoversTest] holds every clause's declared
 * cells to it. One derivation, so the two can never disagree about what a cell's text is.
 */
internal object PortGrid {

    class Cell(val port: String, val text: String, val member: String, val variants: List<String>)

    /**
     * Every port: each non-generic interface of the ports zone, and each generic one the app's composition binds
     * ([bound]). A generic port the composition does not bind is a base (`Listenable`), whose members its ports own.
     */
    val ports: List<KClass<*>> by lazy {
        portInterfaces()
            .map { Class.forName("$PORTS_PACKAGE.$it").kotlin }
            .filter { Port::class.isSuperclassOf(it) && it != Port::class && (it.typeParameters.isEmpty() || it in bound) }
            .sortedBy { it.simpleName }
    }

    /**
     * Each generic port ([Ui]-shaped: a port over the types a screen speaks) with its type arguments as [AppPorts] binds
     * them — a generic port's cells are the cells of what the composition hands it, never of its bare parameters.
     */
    private val bound: Map<KClass<*>, Map<KTypeParameter, KType>> by lazy {
        AppPorts::class.primaryConstructor!!.parameters.map { it.type }.mapNotNull { type ->
            val k = type.classifier as? KClass<*>
            if (k == null || !Port::class.isSuperclassOf(k) || k.typeParameters.isEmpty()) return@mapNotNull null
            k to k.typeParameters.zip(type.arguments.map { it.type!! }).toMap()
        }.toMap()
    }

    val cells: List<Cell> by lazy { (ports.flatMap(::cellsOf) + wireCells()).sortedBy { it.text } }

    /**
     * Every member of a port, or of a handle a port hands out, that declares a throw (`@Throws`), as `Owner.member` —
     * which must be none (`docs/architecture.md`, "A port answers every expected failure as a value"; [PortGridTest]).
     * Read during the derivation of [cells], over exactly the members the grid makes cells of.
     */
    val declaredThrows: List<String> by lazy {
        cells
        throwing.toSortedSet().toList()
    }

    private val throwing = mutableListOf<String>()

    /**
     * The backend's cells (`docs/architecture.md`, "The backend boundary"): one per outcome of every operation's
     * generated reply — `Backend.<operationId> → <Outcome>` for the operation's own, and ONE `Backend.* → <Outcome>`
     * for an outcome several operations answer with a single type. The backend is no port: the app reaches it over
     * the `HttpTransport` port through the gateway, so its owner is named, not a port class.
     */
    private fun wireCells(): List<Cell> {
        val reply = Reply::class
        val routeReplies = reply.sealedSubclasses.filter { it.isSealed && it.simpleName!!.endsWith(REPLY) }
        assertTrue(routeReplies.size >= 10, "port grid: only ${routeReplies.size} route replies — the wire read broke")
        return routeReplies.flatMap { route ->
            val operation = route.simpleName!!.removeSuffix(REPLY).replaceFirstChar { it.lowercase() }
            leaves(route).map { outcome ->
                val name = outcome.simpleName!!
                val own = outcome.java.enclosingClass == route.java
                val owner = if (own) operation else "*"
                Cell(BACKEND, "$BACKEND.$owner → $name", owner, listOf(name))
            }
        }.distinctBy { it.text }
    }

    private fun cellsOf(port: KClass<*>): List<Cell> {
        val name = port.simpleName!!
        val handles = mutableSetOf<KClass<*>>()
        val own = ownedMembers(port).flatMap { memberCells(name, name, it, handles) }
        val handlers = handlerBundleType(port)?.let { bundleType ->
            (bundleType.classifier as KClass<*>).declaredMemberProperties.sortedBy { it.name }.flatMap { f ->
                callbackCells(
                    name,
                    "$name.handlers.${f.name}",
                    f.name,
                    f.returnType,
                    handles,
                    bindingOf(port, bundleType),
                )
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
        if (declaresThrows(member)) throwing += "$owner.${member.name}"
        val returns = variants(member.returnType, plain = RETURNS)
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
        binding: Map<KTypeParameter, KType> = emptyMap(),
    ): List<Cell> {
        val args = type.arguments.dropLast(1).mapNotNull { it.type?.let { arg -> arg.boundBy(binding) } }
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

    fun leaves(k: KClass<*>): List<KClass<*>> =
        k.sealedSubclasses.flatMap { if (it.isSealed) leaves(it) else listOf(it) }.sortedBy { it.qualifiedName }

    fun relativeName(k: KClass<*>): String = k.qualifiedName!!.removePrefix(k.java.`package`.name + ".")

    /**
     * Whether [member] declares a throw (`@Throws`). Kotlin's `@Throws` is source-retained and compiled into the
     * method's `throws` clause, so it is read off the Java method.
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

    /** The handler bundle an event port ([Listenable]) is listened to with, or `null` for a port that is not one. */
    fun handlerBundle(port: KClass<*>): KClass<*>? = handlerBundleType(port)?.classifier as? KClass<*>

    /** [handlerBundle] as a type, its arguments bound as the composition binds [port] ([bound]). */
    private fun handlerBundleType(port: KClass<*>): KType? =
        port.allSupertypes.firstOrNull {
            it.classifier == Listenable::class
        }?.arguments?.single()?.type?.boundBy(bound[port].orEmpty())

    /** [type] as the composition binds it: a generic port's type parameter is what [AppPorts] binds it to. */
    fun bindingFor(type: KType): KType =
        (type.classifier as? KTypeParameter)?.let { p -> bound.values.firstNotNullOfOrNull { it[p] } } ?: type

    /** The argument types an adapter passes [port]'s handler [field], bound as the composition binds [port]. */
    fun handlerArgTypes(port: KClass<*>, field: String): List<KType> {
        val bundleType = handlerBundleType(port)!!
        val f = (bundleType.classifier as KClass<*>).declaredMemberProperties.first { it.name == field }
        return f.returnType.arguments.dropLast(1).mapNotNull { it.type?.boundBy(bindingOf(port, bundleType)) }
    }

    /** The handler bundle's own type parameters, bound through [port]'s binding. */
    private fun bindingOf(port: KClass<*>, bundleType: KType): Map<KTypeParameter, KType> =
        (bundleType.classifier as KClass<*>).typeParameters
            .zip(bundleType.arguments.map { it.type!!.boundBy(bound[port].orEmpty()) }).toMap()

    /** [this] with a type parameter of [binding] replaced by what it is bound to, at the top level only. */
    private fun KType.boundBy(binding: Map<KTypeParameter, KType>): KType = binding[classifier] ?: this

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
    private const val REPLY = "Reply"

    /** The owner of the wire cells — what a clause's `backend(…)` renders, and what the gateway records. */
    const val BACKEND = "Backend"
    const val RETURNS = "returns"
}

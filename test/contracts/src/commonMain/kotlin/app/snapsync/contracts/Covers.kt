package app.snapsync.contracts

import app.snapsync.ports.Port
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * The port-grid cells a clause exercises (`docs/testing.md`, "Every clause declares the cells it covers"), rendered
 * as the grid's own text: `Port.member → Variant`, `Port.handlers.field(Arg, …)`, `Port.member.param(Arg, …)`,
 * `Port.Handle.member → Variant`.
 *
 * Built only through [cells], so a declaration names its port, member and variant by TYPED reference: a rename breaks
 * the compile, and the architecture gate compares the rendered text with the grid it derives. The rendering is common
 * code on purpose — it runs identically on every host, and a run-time check of a declaration renders through it too.
 */
class Covers internal constructor(val cells: List<String>)

/** Declares a clause's cells; at least one is required ([Clause] refuses an empty declaration). */
fun cells(declare: CellsBuilder.() -> Unit): Covers = Covers(CellsBuilder().apply(declare).rendered.toList())

class CellsBuilder internal constructor() {
    internal val rendered = mutableListOf<String>()

    /**
     * The cells of port [P]. The port is a reified type parameter rather than read off a member reference: on
     * Kotlin/Native a member reference carries its name only, not its declaring class.
     */
    inline fun <reified P : Port> on(): PortCells = PortCells(this, simpleNameOf(P::class))

    /** [on], for several cells of one port: `on<SecureStore> { answers(SecureStore::read).with(…); … }`. */
    inline fun <reified P : Port> on(declare: PortCells.() -> Unit) = on<P>().declare()

    @PublishedApi
    internal fun add(cell: String) {
        rendered += cell
    }
}

/** The cells under one owner: a port, or a handle ([handle]) that port hands out. */
class PortCells @PublishedApi internal constructor(
    @PublishedApi internal val builder: CellsBuilder,
    private val owner: String,
) {
    /** The cells of handle [H] as this port hands it out — counted under the port, as the grid counts it. */
    inline fun <reified H : Any> handle(): PortCells = PortCells(builder, "${ownerName()}.${simpleNameOf(H::class)}")

    @PublishedApi
    internal fun ownerName() = owner

    /** [member]'s answer: the variant follows, typed against [member]'s return type ([Answer]). */
    fun <R> answers(member: KCallable<R>): Answer<R> = Answer(builder, "$owner.${member.name} → ")

    /** A `Flow`-returning [member]'s cells, which are the variants of what it emits. */
    fun <R> emits(member: KCallable<Flow<R>>): Answer<R> = Answer(builder, "$owner.${member.name} → ")

    /**
     * An event port's [handler] called by the adapter with one variant per argument, in order: a [KClass] (a sealed
     * leaf or a plain argument type), an enum entry, a `Boolean`, or `null`. Arguments are not type-checked — a handler
     * field is a function type the compiler does not decompose — so the gate is their backstop.
     */
    fun calls(handler: KProperty1<*, Function<*>>, vararg args: Any?) {
        builder.add("$owner.handlers.${handler.name}(${args.joinToString(", ", transform = ::variantName)})")
    }

    /** The callback [member] is handed as parameter [param], called with [args] as [calls] reads them. */
    fun callsBack(member: KCallable<*>, param: String, vararg args: Any?) {
        builder.add("$owner.${member.name}.$param(${args.joinToString(", ", transform = ::variantName)})")
    }
}

/**
 * One answer of a member returning [R]. The variant is checked against [R] where Kotlin's types allow — a sealed leaf
 * must be a subtype, a value must be an [R] (so `null` only on a nullable answer) — and the two-step form is what
 * makes it a check at all: [R] is fixed by the member before the variant is seen, where a single call would widen it.
 */
class Answer<R> internal constructor(private val builder: CellsBuilder, private val prefix: String) {
    /** A sealed leaf of the answer. */
    fun with(variant: KClass<out R & Any>) = builder.add(prefix + variantName(variant))

    /** An enum entry, `true`/`false`, or `null`. */
    fun with(value: R) = builder.add(prefix + variantName(value))

    /**
     * A leaf of a GENERIC sealed answer (`Reply.Ok` of `Reply<T>`), whose class literal is star-projected and so is
     * not a subtype the compiler can accept. Unchecked here; the gate holds it to the grid.
     */
    fun withGenericLeaf(variant: KClass<*>) = builder.add(prefix + variantName(variant))

    /** The member's declared throw (`@Throws` on the port member): the answer of a store that could not look. */
    fun throws() = builder.add(prefix + "throws")

    /** The one cell of an answer that is neither sealed, enum, `Boolean` nor nullable. */
    fun returns() = builder.add(prefix + "returns")
}

/**
 * The grid's name for a variant: a class by its name relative to its package (`SecureStoreRead.Unavailable`), an enum
 * entry as `Enum.ENTRY`, a `Boolean` and `null` as themselves.
 */
internal fun variantName(variant: Any?): String = when (variant) {
    null -> "null"
    is Boolean -> variant.toString()
    is KClass<*> -> relativeNameOf(variant)
    is Enum<*> -> "${simpleNameOf(variant::class)}.${variant.name}"
    else -> error("not a cell variant: $variant — name a class, an enum entry, a Boolean or null")
}

/**
 * [k]'s qualified name without its package. Common code has no package accessor, so the package is the run of
 * lowercase segments ahead of the first capitalised one — true of every package in this repository. `KClass` names are
 * the same on the JVM and Kotlin/Native, which is what lets every host render a declaration identically.
 */
internal fun relativeNameOf(k: KClass<*>): String {
    val segments = requireNotNull(k.qualifiedName) { "a cell variant must be a named class: $k" }.split('.')
    return segments.dropWhile { it.first().isLowerCase() }.joinToString(".")
}

@PublishedApi
internal fun simpleNameOf(k: KClass<*>): String {
    val name = k.simpleName
    requireNotNull(name) { "a cell owner must be a named class: $k" }
    return name
}

package app.snapsync.architecture

import app.snapsync.contracts.CallLog
import app.snapsync.domain.time.Timestamp
import app.snapsync.inbound.Answered
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Granted
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.Running
import app.snapsync.model.TimestampText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every grid port has a recording proxy, and it records every cell of the grid as the grid writes it**
 * (`docs/testing.md`, "A declared cell must occur").
 *
 * The runner fails a clause whose declared cell never occurred, which is only as honest as the proxy that records it. So
 * this drives EVERY cell [PortGrid] derives through the port's proxy (`app.snapsync.contracts.proxy.<Port>Proxy`, built
 * as `(adapter, log)`): an adapter double made by reflection answers the cell's variant — or makes the delivery,
 * calls the callback or the handle the cell names — and the proxy must record exactly that cell
 * for that member, and no other variant of it. A proxy that forgot a member, a handler, a handle or a callback, or
 * renders one under another name, fails here naming the cell. A port another port extends is driven through the
 * extending port's proxy too, since its members are recorded under the port declaring them.
 */
class ProxyCompletenessTest {

    @Test
    fun `every grid port has a proxy`() {
        val missing = PortGrid.ports.filter { proxyClass(it) == null }.map { it.simpleName }
        if (missing.isNotEmpty()) {
            fail(
                "these ports have no recording proxy ($PROXY_PACKAGE.<Port>Proxy, constructed as (adapter, CallLog)): $missing",
            )
        }
    }

    @Test
    fun `every grid cell is recorded by its port's proxy, and through every port extending it`() {
        val failures = PortGrid.ports.flatMap { port ->
            val via = PortGrid.ports.filter { it != port && port.isSuperclassOf(it) }
            (listOf(port) + via).flatMap { through ->
                if (proxyClass(through) == null) return@flatMap emptyList()
                PortGrid.cells.filter { it.port == port.simpleName }.mapNotNull { cell ->
                    val problem = runCatching {
                        Driver(
                            through,
                        ).check(cell.text)
                    }.getOrElse { "${it::class.simpleName}: ${it.message}" }
                    problem?.let { "${cell.text} (through ${through.simpleName}Proxy): $it" }
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "a proxy does not record these cells as the grid writes them:\n  " + failures.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `the drive finds every port`() {
        assertTrue(PortGrid.ports.size > 30, "the port scan found ${PortGrid.ports.size} ports")
    }

    /** Drives one cell through [through]'s proxy over a reflective adapter double. */
    private class Driver(private val through: KClass<*>) {
        private val log = CallLog().also { it.open() }
        private val answers = mutableMapOf<String, (Array<Any?>) -> Any?>()
        private var heard: Any? = null

        private val adapter: Any = double(through) { answers[it] }

        private fun proxy(): Any = proxyClass(through)!!.primaryConstructor!!.call(adapter, log)

        /** Null when [cell] is recorded as written and no other variant of its member is; otherwise the problem. */
        fun check(cell: String): String? {
            val parsed = Parsed(cell)
            when {
                parsed.handler != null -> handlerCall(parsed)
                parsed.handle != null -> handleCell(parsed)
                // The registration itself: answered with a real bundle, which the proxy wraps field by field.
                parsed.name == registration() -> listenAndHear()
                else -> memberCell(parsed)
            }
            if (cell !in log.cells) return "recorded ${log.cells.ifEmpty { setOf("nothing") }}"
            val others = log.cells.filter { it.startsWith(parsed.path + " → ") || it.startsWith(parsed.path + "(") } - cell
            return if (others.isEmpty()) null else "also recorded $others"
        }

        private fun memberCell(p: Parsed) {
            val member = member(through, p.name)
            if (p.param != null) {
                var action: Any? = null
                answers[javaName(member)] = { args ->
                    action = args[paramIndex(member, p.param)]
                    dummy(member.returnType)
                }
                callMember(proxy(), member)
                invokeFunction(action!!, member.parameters.first { it.name == p.param }.type, p.args!!)
            } else {
                drive(member, p.variant!!) { callMember(proxy(), member) }
            }
        }

        /** Answers [variant] from [member], then reads it through [call]. */
        private fun drive(member: KCallable<*>, variant: String, call: () -> Any?) {
            val type = member.returnType
            val k = type.classifier as? KClass<*>
            when {
                k != null && k.isSubclassOf(StateFlow::class) -> {
                    answers[javaName(member)] = { MutableStateFlow(valueOf(type.arguments.single().type!!, variant)) }
                    (call() as StateFlow<*>).value
                }
                k != null && k.isSubclassOf(Flow::class) -> {
                    answers[javaName(member)] = { MutableStateFlow(valueOf(type.arguments.single().type!!, variant)) }
                    runTest { (call() as Flow<*>).first() }
                }
                else -> {
                    answers[javaName(member)] = { valueOf(type, variant, proxied = true) }
                    call()
                }
            }
        }

        private fun handlerCall(p: Parsed) {
            val bundle = listenAndHear()
            val field = bundle::class.memberProperties.first { it.name == p.handler }
            // The argument types as the composition binds the port: a generic port's handler takes its bound types.
            val types = PortGrid.handlerArgTypes(through, field.name)
            check(
                types.size == p.args!!.size,
            ) { "${field.name} takes ${types.size} arguments, the cell names ${p.args}" }
            deliver(field.getter.call(bundle)!!, types.zip(p.args).map { (t, v) -> valueOf(t, v) })
        }

        private fun handleCell(p: Parsed) {
            val handleType = handleClass(p.handle!!)
            val handleAnswers = mutableMapOf<String, (Array<Any?>) -> Any?>()
            val handle = double(handleType) { handleAnswers[it] }
            val wrapped = obtainHandle(handleType, handle)
            val member = member(handleType, p.name)
            if (p.param != null) {
                var action: Any? = null
                handleAnswers[javaName(member)] = { args ->
                    action = args[paramIndex(member, p.param)]
                    Unit
                }
                callMember(wrapped, member)
                invokeFunction(action!!, member.parameters.first { it.name == p.param }.type, p.args!!)
            } else {
                val type = member.returnType
                handleAnswers[javaName(member)] = { valueOf(type, p.variant!!, proxied = true) }
                callMember(wrapped, member)
            }
        }

        /**
         * The handle as the proxy hands it on: from a member returning it, to the adapter as a member's argument, or
         * passed to — or answered by — a delivery the adapter makes.
         */
        private fun obtainHandle(handleType: KClass<*>, handle: Any): Any {
            val producer = allMembers(through).firstOrNull { it.returnType.classifier == handleType }
            if (producer != null) {
                answers[javaName(producer)] = { handle }
                return callMember(proxy(), producer)!!
            }
            val taking = allMembers(
                through,
            ).firstOrNull { m -> valueParams(m).any { it.type.classifier == handleType } }
            if (taking != null) {
                val index = valueParams(taking).indexOfFirst { it.type.classifier == handleType }
                var got: Any? = null
                answers[javaName(taking)] = { args ->
                    got = args[index]
                    dummy(taking.returnType)
                }
                callMember(
                    proxy(),
                    taking,
                    valueParams(taking).mapIndexed { i, p -> if (i == index) handle else dummy(p.type) },
                )
                return got!!
            }
            val bundle = listenAndHear()
            val passing = bundle::class.memberProperties.firstOrNull { f ->
                handlerArgTypes(bundle::class, f.name).any { it.classifier == handleType }
            }
            if (passing != null) {
                val types = handlerArgTypes(bundle::class, passing.name)
                var got: Any? = null
                heardFields[passing.name] = { args -> got = args[types.indexOfFirst { it.classifier == handleType }] }
                deliver(
                    passing.getter.call(bundle)!!,
                    types.map { if (it.classifier == handleType) handle else dummy(it) },
                )
                return got!!
            }
            // A run a delivery answers, which the adapter expires: our handler answers [handle], the proxy wraps it.
            val answering = bundle::class.memberProperties.first { deliveryReturnsRunning(it.returnType) }
            answeredBy[answering.name] = handle
            val types = handlerArgTypes(bundle::class, answering.name)
            return deliver(answering.getter.call(bundle)!!, types.map { dummy(it) })!!
        }

        /** The member [through] registers its handlers through: an event port's `inbound`, or `listen`. */
        private fun registration(): String =
            if (allMembers(through).any { it.name == "inbound" }) "inbound" else PortGrid.LISTEN

        /** What a handler of ours answers, by field, where it answers a handle the test drives. */
        private val answeredBy = mutableMapOf<String, Any>()

        private val heardFields = mutableMapOf<String, (List<Any?>) -> Unit>()

        /** Listens through the proxy with a bundle of doubles, and answers the WRAPPED bundle the adapter was handed. */
        private fun listenAndHear(): Any {
            answers[registration()] = { args ->
                heard = args[0]
                Unit
            }
            val register = allMembers(through).first { it.name == registration() }
            val bundleType = PortGrid.handlerBundle(through)!!
            val ctor = bundleType.primaryConstructor!!
            ctor.isAccessible = true
            val ours = ctor.callBy(
                ctor.parameters.associateWith { param ->
                    val heardBy: (List<Any?>) -> Any? = { args ->
                        heardFields[param.name]?.invoke(args)
                        answeredBy[param.name]
                    }
                    if (isFunctionType(param.type)) {
                        functionDouble(param.type, heardBy)
                    } else {
                        deliveryDouble(param.type, heardBy)
                    }
                },
            )
            callMember(proxy(), register, listOf(ours))
            return heard!!
        }

        /** Calls [fn], a function of [type], with an argument of each of [variants]. */
        private fun invokeFunction(fn: Any, type: KType, variants: List<String>) {
            val types = callbackArgTypes(type)
            check(types.size == variants.size) { "$type takes ${types.size} arguments, the cell names $variants" }
            invoke(fn, types.zip(variants).map { (t, v) -> valueOf(t, v) }, isSuspend(type))
        }
    }

    private class Parsed(cell: String) {
        val variant: String? = cell.substringAfter(" → ", "").ifEmpty { null }
        private val text = if (variant != null) cell.substringBefore(" → ") else cell.substringBefore("(")
        val args: List<String>? = if (variant == null) {
            cell.substringAfter("(").removeSuffix(")").split(", ").filter { it.isNotEmpty() }
        } else {
            null
        }
        private val segments = text.split('.')
        val handler: String? = segments.getOrNull(2)?.takeIf { segments[1] == "handlers" }
        val handle: String? = segments[1].takeIf { it.first().isUpperCase() }
        private val rest = if (handle != null) segments.drop(2) else segments.drop(1)
        val name: String = rest.first()
        val param: String? = rest.getOrNull(1)?.takeIf { handler == null }

        /** The cell's path: what every variant of the same member, handler or callback starts with. */
        val path: String = text
    }

    internal companion object {
        const val PROXY_PACKAGE = "app.snapsync.contracts.proxy"

        fun proxyClass(port: KClass<*>): KClass<*>? = runCatching {
            Class.forName("$PROXY_PACKAGE.${port.simpleName}Proxy").kotlin
        }.getOrNull()?.takeIf { cls ->
            port.isSuperclassOf(cls) && cls.primaryConstructor?.parameters?.map {
                it.type.classifier
            } == listOf(port, CallLog::class)
        }

        fun allMembers(type: KClass<*>): List<KCallable<*>> = type.members.filter { it.isAbstract }

        fun member(type: KClass<*>, name: String): KCallable<*> = allMembers(type).first { it.name == name }

        fun javaName(m: KCallable<*>): String = when (m) {
            is KProperty<*> -> m.javaGetter!!.name
            is KFunction<*> -> m.javaMethod!!.name
            else -> error("not a member: $m")
        }

        fun paramIndex(m: KCallable<*>, param: String): Int =
            m.parameters.filter { it.kind == KParameter.Kind.VALUE }.indexOfFirst { it.name == param }

        fun callMember(receiver: Any, m: KCallable<*>, given: List<Any?>? = null): Any? {
            m.isAccessible = true
            if (m is KProperty<*>) return m.getter.call(receiver)
            val values = m.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val args = mapOf(m.parameters.first { it.kind == KParameter.Kind.INSTANCE } to receiver) +
                values.mapIndexed { i, p -> p to (given?.getOrNull(i) ?: dummy(p.type)) }
            var result: Any? = null
            try {
                if (m.isSuspend) runTest { result = m.callSuspendBy(args) } else result = m.callBy(args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            return result
        }

        fun handlerArgTypes(bundle: KClass<*>, field: String): List<KType> =
            bundle.memberProperties.first { it.name == field }.returnType.let {
                if (isFunctionType(it)) callbackArgTypes(it) else deliveryArgs(it)
            }

        fun callbackArgTypes(type: KType): List<KType> = type.arguments.dropLast(1).mapNotNull { it.type }
            .filterNot { it.classifier == Continuation::class }

        fun isSuspend(type: KType) = type.toString().startsWith("suspend")

        /** Calls a function object with [args], adding a continuation where it is a suspend function. */
        fun invoke(fn: Any, args: List<Any?>, suspending: Boolean) {
            val all = if (suspending) args + DONE else args
            val method = Class.forName("kotlin.jvm.functions.Function${all.size}")
                .getMethod("invoke", *Array(all.size) { Any::class.java })
            try {
                method.invoke(fn, *all.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        val DONE = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }

        fun functionDouble(type: KType, body: (List<Any?>) -> Any?): Any {
            val arity = callbackArgTypes(type).size + if (isSuspend(type)) 1 else 0
            val fn = Class.forName("kotlin.jvm.functions.Function$arity")
            // A handler's answer is the core's, so a double answers null: a type-checked `Unit` would fail a handler whose
            // answer is a nullable value (`onEvent`), and `null` passes every reference type's cast.
            return Proxy.newProxyInstance(fn.classLoader, arrayOf(fn)) { self, method, args ->
                if (method.declaringClass == Any::class.java) {
                    objectMethod(
                        self,
                        method,
                        args,
                    )
                } else {
                    body(args.orEmpty().toList().take(callbackArgTypes(type).size))
                }
            }
        }

        /**
         * A double of interface [type]: a member [configured] answers by name gives that answer (`null` included), every
         * other answers a non-null dummy of its return type.
         */
        fun double(type: KClass<*>, configured: (String) -> ((Array<Any?>) -> Any?)?): Any {
            val returns = allMembers(type).associate { javaName(it) to it.returnType }
            return Proxy.newProxyInstance(
                type.java.classLoader,
                arrayOf(type.java),
                InvocationHandler { self, method, args ->
                    if (method.declaringClass == Any::class.java) {
                        return@InvocationHandler objectMethod(
                            self,
                            method,
                            args,
                        )
                    }
                    val answer = configured(method.name)
                    if (answer != null) {
                        answer(
                            args?.let {
                                arrayOf<Any?>(*it)
                            } ?: emptyArray(),
                        )
                    } else {
                        returns[method.name]?.let(::returned) ?: Unit
                    }
                },
            )
        }

        fun objectMethod(self: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args!![0]
            else -> "double"
        }

        /** The value of [type] whose variant renders as [variant] — as a JDK proxy answers it when [proxied]. */
        fun valueOf(type: KType, variant: String, proxied: Boolean = false): Any? {
            val k = type.classifier as? KClass<*>
            return when {
                variant == "null" -> null
                proxied && k != null && k.isValue -> returned(type)
                variant == "true" || variant == "false" -> variant.toBoolean()
                k != null && k.java.isEnum -> k.java.enumConstants.first { "${k.simpleName}.${(it as Enum<*>).name}" == variant }
                k != null && k.isSealed -> PortGrid.leaves(
                    k,
                ).first { PortGrid.relativeName(it) == variant }.let(::instance)
                else -> dummy(type)
            }
        }

        fun dummy(type: KType): Any? {
            val k = PortGrid.bindingFor(type).classifier as? KClass<*> ?: return null
            return when {
                // Every date role wraps a Timestamp and refuses one that is not an instant; a port is called with real ones.
                k == Timestamp::class -> TimestampText.parse("2026-01-01T00:00:00Z")
                k == String::class -> "d"
                k == Int::class -> 0
                k == Long::class -> 0L
                k == Boolean::class -> false
                k == Unit::class -> Unit
                k == ByteArray::class -> ByteArray(0)
                k == List::class || k == Collection::class -> emptyList<Any>()
                k == Set::class -> emptySet<Any>()
                k == Map::class -> emptyMap<Any, Any>()
                k.isSubclassOf(Flow::class) -> MutableStateFlow(dummy(type.arguments.single().type!!))
                k.isSubclassOf(Function::class) -> functionDouble(type) { null }
                k.java.isEnum -> k.java.enumConstants.first()
                k.isSealed -> instance(PortGrid.leaves(k).first())
                k.java.isInterface -> double(k) { null }
                // A value class is unboxed at a typed call, so its one field must be real.
                k.isValue -> k.primaryConstructor!!.let { c ->
                    c.isAccessible = true
                    c.call(dummy(c.parameters.single().type))
                }
                else -> instance(k)
            }
        }

        /**
         * [dummy] as a JDK proxy returns it: a value class crosses the proxy's erased signature as its one field (a
         * nullable value class over a reference included), so the caller unboxes nothing.
         */
        fun returned(type: KType): Any? {
            val k = PortGrid.bindingFor(type).classifier as? KClass<*>
            return if (k != null && k.isValue) dummy(k.primaryConstructor!!.parameters.single().type) else dummy(type)
        }

        fun instance(k: KClass<*>): Any = k.objectInstance ?: allocate(k.java)

        fun allocate(c: Class<*>): Any {
            val unsafe = Class.forName(
                "sun.misc.Unsafe",
            ).getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c)
        }
    }
}

private fun isFunctionType(type: KType): Boolean = (type.classifier as? KClass<*>)?.isSubclassOf(Function::class) == true

private fun valueParams(m: KCallable<*>): List<KParameter> = m.parameters.filter { it.kind == KParameter.Kind.VALUE }

/** A handle the grid names: a port's own, or one the inbound kinds hand across (`Completion`, `Running`). */
private fun handleClass(name: String): KClass<*> = listOf("app.snapsync.ports", "app.snapsync.inbound").firstNotNullOf {
    runCatching { Class.forName("$it.$name").kotlin }.getOrNull()
}

/**
 * Makes [args] — what an adapter passes — as the delivery [handler] of its inbound kind: its argument (`Unit`
 * where it takes none) and, for a `Background` or `Granted` one, the completion. Answers what it answered.
 */
private fun deliver(handler: Any, args: List<Any?>): Any? = when (handler) {
    is Function<*> -> ProxyCompletenessTest.invoke(handler, args, suspending = false)
    is Background<*> -> {
        @Suppress("UNCHECKED_CAST")
        val background = handler as Background<Any?>
        background.handle(if (args.size == 2) args[0] else Unit, args.last() as Completion)
    }
    is Granted<*> -> {
        @Suppress("UNCHECKED_CAST")
        val granted = handler as Granted<Any?>
        granted.handle(if (args.size == 2) args[0] else Unit, args.last() as Completion)
    }
    is Inbound<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (handler as Inbound<Any?, Any?>).deliver(args.singleOrNull() ?: Unit)
    }
    is Answered<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (handler as Answered<Any?, Any?>).answer(args.singleOrNull() ?: Unit)
    }
    else -> error("not a delivery: $handler")
}

/**
 * A double of a delivery of [type] (an inbound kind): it hands [body] what it was delivered — its argument
 * (none for `Unit`) and, for a `Background` or `Granted` one, the completion — and answers what [body] answers,
 * or for a `Granted` one a run that expires nothing when [body] answers none.
 */
private fun deliveryDouble(type: KType, body: (List<Any?>) -> Any?): Any {
    val k = type.classifier as KClass<*>
    return Proxy.newProxyInstance(k.java.classLoader, arrayOf(k.java)) { self, method, args ->
        if (method.declaringClass == Any::class.java) {
            return@newProxyInstance ProxyCompletenessTest.objectMethod(
                self,
                method,
                args,
            )
        }
        val given = args.orEmpty().toList().filterNot { it == Unit }
        body(given) ?: if (k == Granted::class) ProxyCompletenessTest.double(Running::class) { null } else null
    }
}

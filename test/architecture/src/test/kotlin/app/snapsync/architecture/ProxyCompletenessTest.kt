package app.snapsync.architecture

import app.snapsync.contracts.CallLog
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every grid port has a recording proxy, and it records every cell of the grid as the grid writes it**
 * (`docs/testing.md`, "A declared cell must occur").
 *
 * The runner fails a clause whose declared cell never occurred, which is only as honest as the proxy that records it. So
 * this drives EVERY cell [PortGrid] derives through the port's proxy (`app.snapsync.contracts.proxy.<Port>Proxy`, built
 * as `(adapter, log)`): an adapter double made by reflection answers the cell's variant — or throws the member's
 * declared type, or calls the handler, callback or handle the cell names — and the proxy must record exactly that cell
 * for that member, and no other variant of it. A proxy that forgot a member, a handler, a handle or a callback, or
 * renders one under another name, fails here naming the cell. A port another port extends is driven through the
 * extending port's proxy too, since its members are recorded under the port declaring them.
 *
 * A proxy names each member's `@Throws` itself (Kotlin/Native cannot read the annotation); its `THROWS` must equal the
 * port's declarations exactly, and a throw of another type or a cancellation must pass unrecorded.
 */
class ProxyCompletenessTest {

    @Test
    fun `every grid port has a proxy`() {
        val missing = PortGrid.ports.filter { proxyClass(it) == null }.map { it.simpleName }
        if (missing.isNotEmpty()) {
            fail("these ports have no recording proxy ($PROXY_PACKAGE.<Port>Proxy, constructed as (adapter, CallLog)): $missing")
        }
    }

    @Test
    fun `every grid cell is recorded by its port's proxy, and through every port extending it`() {
        val failures = PortGrid.ports.flatMap { port ->
            val via = PortGrid.ports.filter { it != port && port.isSuperclassOf(it) }
            (listOf(port) + via).flatMap { through ->
                if (proxyClass(through) == null) return@flatMap emptyList()
                PortGrid.cells.filter { it.port == port.simpleName }.mapNotNull { cell ->
                    val problem = runCatching { Driver(through).check(cell.text) }.getOrElse { "${it::class.simpleName}: ${it.message}" }
                    problem?.let { "${cell.text} (through ${through.simpleName}Proxy): $it" }
                }
            }
        }
        if (failures.isNotEmpty()) fail("a proxy does not record these cells as the grid writes them:\n  " + failures.joinToString("\n  "))
    }

    @Test
    fun `every proxy names exactly its port's declared throws`() {
        val wrong = PortGrid.ports.mapNotNull { port ->
            val proxy = proxyClass(port) ?: return@mapNotNull null
            val declared = ownMembers(port).mapNotNull { m ->
                val types = (m as? KFunction<*>)?.javaMethod?.exceptionTypes.orEmpty()
                types.singleOrNull()?.let { m.name to it.kotlin }
            }.toMap()
            @Suppress("UNCHECKED_CAST")
            val named = (proxy.companionObjectInstance?.let { c -> c::class.memberProperties.firstOrNull { it.name == "THROWS" }?.getter?.call(c) }
                as Map<String, KClass<*>>?).orEmpty()
            if (named == declared) null else "${port.simpleName}Proxy names $named, the port declares $declared"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
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
                answers[javaName(member)] = { args -> action = args[paramIndex(member, p.param)]; dummy(member.returnType) }
                callMember(proxy(), member)
                invokeFunction(action!!, member.parameters.first { it.name == p.param }.type, p.args!!)
            } else {
                drive(member, p.variant!!) { callMember(proxy(), member) }
            }
        }

        /** Answers [variant] from [member] (a value, or a throw of the declared type), then reads it through [call]. */
        private fun drive(member: KCallable<*>, variant: String, call: () -> Any?) {
            val type = member.returnType
            val k = type.classifier as? KClass<*>
            if (variant == "throws") {
                val declared = (member as KFunction<*>).javaMethod!!.exceptionTypes.single()
                answers[javaName(member)] = { throw allocate(declared) as Throwable }
                runCatching { call() }
                // A throw of another type, or a cancellation, passes unrecorded; the cell is already in the log once.
                val before = log.cells
                answers[javaName(member)] = { throw AssertionError("not the declared type") }
                runCatching { call() }
                answers[javaName(member)] = { throw CancellationException("cancelled") }
                runCatching { call() }
                check(log.cells == before) { "an undeclared throw was recorded: ${log.cells - before}" }
                return
            }
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
                    answers[javaName(member)] = { valueOf(type, variant) }
                    call()
                }
            }
        }

        private fun handlerCall(p: Parsed) {
            val bundle = listenAndHear()
            val field = bundle::class.memberProperties.first { it.name == p.handler }
            invokeFunction(field.getter.call(bundle)!!, field.returnType, p.args!!)
        }

        private fun handleCell(p: Parsed) {
            val handleType = Class.forName("app.snapsync.ports.${p.handle}").kotlin
            val handleAnswers = mutableMapOf<String, (Array<Any?>) -> Any?>()
            val handle = double(handleType) { handleAnswers[it] }
            val wrapped = obtainHandle(handleType, handle)
            val member = member(handleType, p.name)
            if (p.param != null) {
                var action: Any? = null
                handleAnswers[javaName(member)] = { args -> action = args[paramIndex(member, p.param)]; Unit }
                callMember(wrapped, member)
                invokeFunction(action!!, member.parameters.first { it.name == p.param }.type, p.args!!)
            } else {
                val type = member.returnType
                handleAnswers[javaName(member)] = { valueOf(type, p.variant!!) }
                callMember(wrapped, member)
            }
        }

        /** The handle as the proxy hands it on: from a member returning it, or from a handler it is passed to. */
        private fun obtainHandle(handleType: KClass<*>, handle: Any): Any {
            val producer = allMembers(through).firstOrNull { it.returnType.classifier == handleType }
            if (producer != null) {
                answers[javaName(producer)] = { handle }
                return callMember(proxy(), producer)!!
            }
            val bundle = listenAndHear()
            val field = bundle::class.memberProperties.first { f -> handlerArgTypes(bundle::class, f.name).any { it.classifier == handleType } }
            val types = handlerArgTypes(bundle::class, field.name)
            var got: Any? = null
            heardFields[field.name] = { args -> got = args[types.indexOfFirst { it.classifier == handleType }] }
            invoke(field.getter.call(bundle)!!, types.map { if (it.classifier == handleType) handle else dummy(it) }, isSuspend(field.returnType))
            return got!!
        }

        private val heardFields = mutableMapOf<String, (List<Any?>) -> Unit>()

        /** Listens through the proxy with a bundle of doubles, and answers the WRAPPED bundle the adapter was handed. */
        private fun listenAndHear(): Any {
            answers["listen"] = { args -> heard = args[0]; Unit }
            val listen = allMembers(through).first { it.name == "listen" }
            val bundleType = PortGrid.handlerBundle(through)!!
            val ctor = bundleType.primaryConstructor!!
            val ours = ctor.callBy(
                ctor.parameters.associateWith { param ->
                    functionDouble(param.type) { args -> heardFields[param.name]?.invoke(args); null }
                },
            )
            callMember(proxy(), listen, listOf(ours))
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

    private companion object {
        const val PROXY_PACKAGE = "app.snapsync.contracts.proxy"

        fun proxyClass(port: KClass<*>): KClass<*>? = runCatching {
            Class.forName("$PROXY_PACKAGE.${port.simpleName}Proxy").kotlin
        }.getOrNull()?.takeIf { cls ->
            port.isSuperclassOf(cls) && cls.primaryConstructor?.parameters?.map { it.type.classifier } == listOf(port, CallLog::class)
        }

        fun ownMembers(port: KClass<*>) = port.members.filter { it.isAbstract && it.name != "listen" }

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
            callbackArgTypes(bundle.memberProperties.first { it.name == field }.returnType)

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
                if (method.declaringClass == Any::class.java) objectMethod(self, method, args) else body(args.orEmpty().toList().take(callbackArgTypes(type).size))
            }
        }

        /**
         * A double of interface [type]: a member [configured] answers by name gives that answer (`null` included), every
         * other answers a non-null dummy of its return type.
         */
        fun double(type: KClass<*>, configured: (String) -> ((Array<Any?>) -> Any?)?): Any {
            val returns = allMembers(type).associate { javaName(it) to it.returnType }
            return Proxy.newProxyInstance(type.java.classLoader, arrayOf(type.java), InvocationHandler { self, method, args ->
                if (method.declaringClass == Any::class.java) return@InvocationHandler objectMethod(self, method, args)
                val answer = configured(method.name)
                if (answer != null) answer(args?.let { arrayOf<Any?>(*it) } ?: emptyArray()) else returns[method.name]?.let(::dummy) ?: Unit
            })
        }

        fun objectMethod(self: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args!![0]
            else -> "double"
        }

        /** The value of [type] whose variant renders as [variant]. */
        fun valueOf(type: KType, variant: String): Any? {
            val k = type.classifier as? KClass<*>
            return when {
                variant == "null" -> null
                variant == "true" || variant == "false" -> variant.toBoolean()
                k != null && k.java.isEnum -> k.java.enumConstants.first { "${k.simpleName}.${(it as Enum<*>).name}" == variant }
                k != null && k.isSealed -> leaves(k).first { relativeName(it) == variant }.let(::instance)
                else -> dummy(type)
            }
        }

        fun dummy(type: KType): Any? {
            val k = type.classifier as? KClass<*> ?: return null
            return when {
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
                k.isSealed -> instance(leaves(k).first())
                k.java.isInterface -> double(k) { null }
                // A value class is unboxed at a typed call, so its one field must be real.
                k.isValue -> k.primaryConstructor!!.let { c -> c.isAccessible = true; c.call(dummy(c.parameters.single().type)) }
                else -> instance(k)
            }
        }

        fun instance(k: KClass<*>): Any = k.objectInstance ?: allocate(k.java)

        fun allocate(c: Class<*>): Any {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c)
        }

        fun leaves(k: KClass<*>): List<KClass<*>> = k.sealedSubclasses.flatMap { if (it.isSealed) leaves(it) else listOf(it) }

        fun relativeName(k: KClass<*>): String = k.qualifiedName!!.removePrefix(k.java.`package`.name + ".")
    }
}

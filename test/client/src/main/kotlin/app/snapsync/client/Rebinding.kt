package app.snapsync.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.rpc.withService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass

/**
 * The typed service [type] over this connection, each call made on the client live WHEN it is made — so a service a
 * caller holds outlives the connection it was taken on. kotlinx-rpc's own stub is bound to one client for good.
 */
internal fun <T : Any> RpcConnection.service(type: KClass<T>): T =
    type.java.cast(Proxy.newProxyInstance(type.java.classLoader, arrayOf(type.java), Rebinding(type, this)))

private class Rebinding<T : Any>(private val type: KClass<T>, private val connection: RpcConnection) :
    InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args.orEmpty()
        if (method.declaringClass == Any::class.java) return objectMethod(proxy, method, arguments)
        @Suppress("UNCHECKED_CAST")
        val continuation = arguments.lastOrNull() as? Continuation<Any?>
        return when {
            continuation != null -> {
                val plain = Array<Any?>(arguments.size - 1) { arguments[it] }
                val call: suspend () -> Any? = {
                    connection.sending { client ->
                        suspendCoroutineUninterceptedOrReturn<Any?> { resume ->
                            method.on(client.withService(type), arrayOf<Any?>(*plain, resume))
                        }
                    }
                }
                call.startCoroutineUninterceptedOrReturn(continuation)
            }
            // A stream, opened on the connection live when it is collected.
            Flow::class.java.isAssignableFrom(method.returnType) -> flow<Any?> {
                @Suppress("UNCHECKED_CAST")
                emitAll(method.on(connection.client.withService(type), arguments) as Flow<Any?>)
            }
            else -> method.on(connection.client.withService(type), arguments)
        }
    }

    private fun objectMethod(proxy: Any, method: Method, arguments: Array<out Any?>): Any = when (method.name) {
        "equals" -> proxy === arguments[0]
        "hashCode" -> System.identityHashCode(proxy)
        else -> "${type.simpleName} over ${connection.url}"
    }
}

private fun Method.on(target: Any, arguments: Array<out Any?>): Any? = try {
    invoke(target, *arguments)
} catch (e: InvocationTargetException) {
    throw e.targetException
}

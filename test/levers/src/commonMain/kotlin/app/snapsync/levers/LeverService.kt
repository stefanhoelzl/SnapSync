package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.RpcCallable
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

/**
 * **A mocked system's operator face as a typed service** (`docs/testing.md`, "Typed services"): an `@Rpc`
 * interface whose companion object extends this, naming the service once — its route [name] and what a host must
 * offer before any of its methods is honoured ([needs]).
 *
 * The control channel serves every method as `/device/<name>/<method-in-kebab>`, derived from the [descriptor] the
 * kotlinx-rpc compiler plugin generates: a method's parameters are the verb's arguments and its return type is its
 * reply — `Unit` for a lever, the value for a read. A test calls the same interface through a client built from the
 * same descriptor, so a renamed lever or a changed type is a compile error, not a `400` at run time.
 *
 * A method that needs more than its service's [needs] does not belong in it: it lives in a service declaring those.
 */
@OptIn(ExperimentalRpcApi::class)
abstract class LeverService<@Rpc T : Any>(
    /** The route segment: `/device/<name>/…`. */
    val name: String,
    /** Every need a host must meet to honour this service's methods; one unmet refuses them all, with its reason. */
    val needs: Set<LeverNeed>,
    descriptor: () -> RpcServiceDescriptor<T>,
) {
    /** The compiler plugin's description of the interface: its methods, their parameters and an invoker. */
    val descriptor: RpcServiceDescriptor<T> by lazy(descriptor)

    /** The `/device` verb of each method, by the method's name, in declaration order. */
    val verbs: Map<String, String> by lazy {
        this.descriptor.callables.keys.associateWith { "$name/${kebab(it)}" }
    }

    /** The method [verb] (`<name>/<method-in-kebab>`) names, or `null`. */
    fun callableOf(verb: String): RpcCallable<T>? =
        verbs.entries.firstOrNull { it.value == verb }?.let { this.descriptor.getCallable(it.key) }

    override fun toString(): String = "LeverService($name)"
}

/** What a host must offer before a service's methods are honoured. */
enum class LeverNeed(val what: String) {
    /** The backend is the mock, whose operator the service is: the levers a real backend has no route for. */
    BACKEND_MOCKED("the backend mock's operator"),

    /**
     * The backend's public surface is reachable from the channel: the routes any device or member calls, so these
     * methods work against the real `api/` as well as the mock.
     */
    BACKEND_REACHABLE("the backend's public surface"),
}

/**
 * On an `event` parameter: omitted (`null`), it names the event this device is joined to — the channel resolves it
 * before the call reaches the service, and answers `400` when the device is joined to none.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class JoinedEvent

/** On a `device` parameter: omitted (`null`), it names this device — the channel resolves it before the call. */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ThisDevice

/**
 * On a public-route method: the operator [members] it stands in for — what a test reaches through the backend's public
 * surface instead of through the operator, so it holds against the real `api/` too. The gate (`LeverFacesTest`) accepts
 * a face member that is no method of its interface only when a public-route method names it here.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class StandsInFor(vararg val members: String)

/** Every service the channel serves. A face migrates by joining this list; nothing else registers it. */
val LEVER_SERVICES: List<LeverService<*>> = listOf<LeverService<*>>(BackendLevers, PublicBackendLevers)

/** `refuseNextCredential` → `refuse-next-credential`. */
fun kebab(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

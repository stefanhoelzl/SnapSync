package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlin.reflect.KClass

/**
 * **A mocked system's operator face as a typed service** (`docs/testing.md`, "Typed services"): an `@Rpc`
 * interface whose companion object extends this, naming the service once — its [name] and what a host must offer
 * before any of its methods is honoured ([needs]).
 *
 * The control channel serves it over kRPC: the kotlinx-rpc compiler plugin generates the client a test calls and the
 * server stub the rig registers the implementation under, so a renamed lever or a changed type is a compile error. A
 * method's parameters are its arguments and its return type is its reply — `Unit` for a lever, the value for a read.
 *
 * A method that needs more than its service's [needs] does not belong in it: it lives in a service declaring those.
 */
@OptIn(ExperimentalRpcApi::class)
abstract class LeverService<@Rpc T : Any>(
    /** The interface, which the channel registers its implementation under. */
    val type: KClass<T>,
    /** The service's name, by which a host classifies it (`<name>.<method>`) and a refusal names it. */
    val name: String,
    /** Every need a host must meet to honour this service's methods; one unmet refuses them all, with its reason. */
    val needs: Set<LeverNeed>,
    descriptor: () -> RpcServiceDescriptor<T>,
) {
    /** The compiler plugin's description of the interface: its methods, their parameters and an invoker. */
    val descriptor: RpcServiceDescriptor<T> by lazy(descriptor)

    /** Each method's entry in a host's advertisement — `<name>.<method>` — in declaration order. */
    val entries: List<String> by lazy { this.descriptor.callables.keys.map { "$name.$it" } }

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
val LEVER_SERVICES: List<LeverService<*>> = listOf<LeverService<*>>(RigLevers, WorldLevers, BackendLevers, PublicBackendLevers)

/**
 * A host refused a service's method: one of the service's needs is unmet here. kRPC carries it to the client as this
 * same class (it is rebuilt by name), so a caller tells a refusal from a failure by its type.
 */
class LeverRefused(message: String) : IllegalStateException(message)

package app.snapsync.rig

import app.snapsync.levers.BackendLevers
import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverNeed
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.LeverService
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.ThisDevice
import app.snapsync.levers.WorldLevers
import app.snapsync.mock.MockedSystem
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.descriptor.RpcInvokator
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

// The typed services as this host serves them (`docs/testing.md`, "Typed services"). The rig registers every declared
// service (`LEVER_SERVICES`) on its kRPC route, each behind ONE wrapper written for all of them: it names the joined event
// and this device for an omitted `@JoinedEvent` / `@ThisDevice` argument, refuses every method with the reason when the
// host cannot meet the service's needs, runs each call as an act of the host's (`RigActs`), and calls the implementation
// through the descriptor's invoker — so a face that migrates adds an interface and an implementation, and nothing here.

/** A declared service as this world serves it: the wrapper the kRPC route registers, and why it refuses, if it does. */
class ServedService internal constructor(val service: LeverService<*>, internal val wrapper: Any, val refusal: String?)

/** The services the channel serves itself rather than over the mocks: what it advertises, and the world's events. */
private val CHANNEL_SERVICES: Set<LeverService<*>> = setOf(RigLevers, WorldLevers)

/** Every declared service but the channel's own, each call run as one of [acts]. */
fun MockWorld.servedServices(acts: RigActs = RigActs.Direct): List<ServedService> =
    LEVER_SERVICES.filter { it !in CHANNEL_SERVICES }.map { service ->
        val refusal = refusalFor(service.needs.map(::needOf), service.name)
        served(service, if (refusal == null) implementationOf(service) else null, refusal, acts, resolving(this))
    }

/** The channel's own service: what this host advertises. */
internal fun servedRig(advertise: () -> DeviceAdvertisement): ServedService =
    ServedService(
        RigLevers,
        object : RigLevers {
            override suspend fun advertise() = advertise()
        },
        refusal = null,
    )

/**
 * The world's events and clock, served by a host that can say when its app is settled — or refused, with [refusal], by
 * one that cannot. Never an act itself: a call that moves the world ([WorldLevers.advance]) runs its own.
 */
fun servedWorld(implementation: WorldLevers?, refusal: String?): ServedService =
    served(WorldLevers, implementation, refusal ?: "no world is served".takeIf { implementation == null }, RigActs.Direct, null)

/** Why a device host refuses the world's events: its operating system runs work the channel cannot see. */
const val WORLD_REFUSED_ON_A_DEVICE: String =
    "this host's operating system runs work the channel cannot see, so it cannot say when the app is settled — the " +
        "event stream is the JVM host's (`docs/testing.md`, \"How a test waits\")"

/** Every typed service's methods, as a host's advertisement names them (`<service>.<method>`). */
internal val SERVICE_ENTRIES: List<String> = LEVER_SERVICES.filter { it !== RigLevers }.flatMap { it.entries }

/** The refusal of each method of every service this world refuses, by advertisement entry. */
internal fun MockWorld.serviceRefusals(): Map<String, String> = servedServices().flatMap { served ->
    served.refusal?.let { why -> served.service.entries.map { it to why } }.orEmpty()
}.toMap()

private fun MockWorld.implementationOf(service: LeverService<*>): Any = when (service) {
    BackendLevers -> device.backend.operator.also { check(isMocked(MockedSystem.BACKEND)) }
    PublicBackendLevers -> PublicBackend(this, checkNotNull(reach))
    else -> error("$service has no implementation in this world")
}

private fun needOf(need: LeverNeed): Need = when (need) {
    LeverNeed.BACKEND_MOCKED -> Need.Operator
    LeverNeed.BACKEND_REACHABLE -> Need.Reach
}

/** How an omitted `@JoinedEvent` / `@ThisDevice` argument is named: by this world's joined event and device. */
private fun resolving(world: MockWorld) = Resolve(joinedEvent = world.joinedEventId, ownDevice = world.ownDeviceId)

/** What names an omitted argument; `null` for a service whose methods take none. */
private class Resolve(val joinedEvent: () -> String?, val ownDevice: () -> String)

@OptIn(ExperimentalRpcApi::class)
private fun served(
    service: LeverService<*>,
    implementation: Any?,
    refusal: String?,
    acts: RigActs,
    resolve: Resolve?,
): ServedService {
    @Suppress("UNCHECKED_CAST")
    val typed = service as LeverService<Any>
    val wrapper = typed.descriptor.createInstance(0L, Resolving(typed, implementation, refusal, acts, resolve))
    return ServedService(service, wrapper, refusal)
}

/**
 * The wrapper's calls: what the generated stub of a service's interface hands an [RpcClient] — here not sent anywhere,
 * but resolved and invoked on the implementation in this process.
 */
@OptIn(ExperimentalRpcApi::class)
private class Resolving(
    private val service: LeverService<Any>,
    private val implementation: Any?,
    private val refusal: String?,
    private val acts: RigActs,
    private val resolve: Resolve?,
) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T {
        log.i { "→ ${service.name}.${call.callableName}" }
        refusal?.let { throw LeverRefused("${service.name}.${call.callableName} is refused here: $it") }
        val arguments = arguments(call)
        @Suppress("UNCHECKED_CAST")
        return acts.act("${service.name}.${call.callableName}") { invoke(call, arguments) as T }
    }

    /** A method returning a `Flow`: the implementation's flow, invoked once it is collected. */
    override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
        log.i { "→ ${service.name}.${call.callableName} (stream)" }
        refusal?.let { throw LeverRefused("${service.name}.${call.callableName} is refused here: $it") }
        @Suppress("UNCHECKED_CAST")
        emitAll(invoke(call, arguments(call)) as Flow<T>)
    }

    private fun arguments(call: RpcCall): Array<Any?> {
        val callable = checkNotNull(service.descriptor.getCallable(call.callableName))
        val arguments = call.parameters.copyOf()
        callable.parameters.forEachIndexed { i, parameter ->
            if (arguments[i] != null) return@forEachIndexed
            when {
                parameter.annotations.any { it is JoinedEvent } -> arguments[i] = checkNotNull(resolve).joinedEvent()
                    ?: throw IllegalArgumentException("no joined event, and no `event` was named")
                parameter.annotations.any { it is ThisDevice } -> arguments[i] = checkNotNull(resolve).ownDevice()
            }
        }
        return arguments
    }

    private suspend fun invoke(call: RpcCall, arguments: Array<Any?>): Any? {
        val callable = checkNotNull(service.descriptor.getCallable(call.callableName))
        val invoker = callable.invokator as RpcInvokator.Method<Any>
        return invoker.call(checkNotNull(implementation), arguments)
    }

    private companion object {
        val log = Logger.withTag("rig")
    }
}

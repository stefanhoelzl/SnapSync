package app.snapsync.rig

import app.snapsync.levers.BackendLevers
import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverNeed
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.LeverService
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.ThisDevice
import app.snapsync.mock.MockedSystem
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.descriptor.RpcInvokator
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

// The typed services as this host serves them (`docs/testing.md`, "Typed services"). The rig registers every declared
// service (`LEVER_SERVICES`) on its kRPC route, each behind ONE wrapper written for all of them: it names the joined event
// and this device for an omitted `@JoinedEvent` / `@ThisDevice` argument, refuses every method with the reason when the
// host cannot meet the service's needs, and calls the implementation through the descriptor's invoker — so a face that
// migrates adds an interface and an implementation, and nothing here.

/** A declared service as this world serves it: the wrapper the kRPC route registers, and why it refuses, if it does. */
class ServedService internal constructor(val service: LeverService<*>, internal val wrapper: Any, val refusal: String?)

/** Every declared service but the channel's own ([RigLevers], which the server serves over its hooks). */
fun MockWorld.servedServices(): List<ServedService> =
    LEVER_SERVICES.filter { it !== RigLevers }.map { service ->
        val refusal = refusalFor(service.needs.map(::needOf), service.name)
        served(service, if (refusal == null) implementationOf(service) else null, refusal)
    }

/** The channel's own service: what this host advertises. */
internal fun servedRig(advertise: () -> app.snapsync.levers.DeviceAdvertisement): ServedService =
    ServedService(
        RigLevers,
        object : RigLevers {
            override suspend fun advertise() = advertise()
        },
        refusal = null,
    )

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

@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.served(service: LeverService<*>, implementation: Any?, refusal: String?): ServedService {
    @Suppress("UNCHECKED_CAST")
    val typed = service as LeverService<Any>
    val wrapper = typed.descriptor.createInstance(0L, Resolving(this, typed, implementation, refusal))
    return ServedService(service, wrapper, refusal)
}

/**
 * The wrapper's calls: what the generated stub of a service's interface hands an [RpcClient] — here not sent anywhere,
 * but resolved and invoked on the implementation in this process.
 */
@OptIn(ExperimentalRpcApi::class)
private class Resolving(
    private val world: MockWorld,
    private val service: LeverService<Any>,
    private val implementation: Any?,
    private val refusal: String?,
) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T {
        log.i { "→ ${service.name}.${call.callableName}" }
        refusal?.let { throw LeverRefused("${service.name}.${call.callableName} is refused here: $it") }
        val callable = checkNotNull(service.descriptor.getCallable(call.callableName))
        val arguments = call.parameters.copyOf()
        callable.parameters.forEachIndexed { i, parameter ->
            if (arguments[i] != null) return@forEachIndexed
            when {
                parameter.annotations.any { it is JoinedEvent } ->
                    arguments[i] = world.joinedEventId() ?: throw IllegalArgumentException("no joined event, and no `event` was named")
                parameter.annotations.any { it is ThisDevice } -> arguments[i] = world.ownDeviceId()
            }
        }
        val invoker = callable.invokator as RpcInvokator.Method<Any>
        @Suppress("UNCHECKED_CAST")
        return invoker.call(checkNotNull(implementation), arguments) as T
    }

    override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
        error("${service.name}.${call.callableName}: no service of the channel streams yet")

    private companion object {
        val log = Logger.withTag("rig")
    }
}

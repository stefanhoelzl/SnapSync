package app.snapsync.control

import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverService
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * A typed service's methods, called over the channel (`docs/testing.md`, "Typed services"): `client.levers(
 * BackendLevers).offline(true)` posts `/device/backend/offline` with the arguments as one JSON object, each encoded by
 * its parameter's serializer, and decodes the reply by the return type's. The client is generated from the same
 * descriptor the host dispatches by, so a renamed lever or a changed type fails to compile here.
 *
 * A method answers its value or throws: [LeverRefused] when this host refuses the service (`409`, with the reason),
 * [IllegalStateException] for anything else the host answered.
 */
@OptIn(ExperimentalRpcApi::class)
fun <T : Any> RigClient.levers(service: LeverService<T>): T = service.descriptor.createInstance(
    service.hashCode().toLong(),
    LeverClient(this),
)

/** The host refused a typed service's method: one of its service's needs is unmet here. */
class LeverRefused(val verb: String, val reason: String) : IllegalStateException("the host refused $verb: $reason")

@OptIn(ExperimentalRpcApi::class)
private class LeverClient(private val rig: RigClient) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T {
        val service = LEVER_SERVICES.single { it.descriptor.fqName == call.descriptor.fqName }
        val callable =
            checkNotNull(
                service.descriptor.getCallable(call.callableName),
            ) { "${service.name} has no ${call.callableName}" }
        val verb = service.verbs.getValue(call.callableName)
        val body = buildJsonObject {
            callable.parameters.forEachIndexed { i, parameter ->
                put(parameter.name, json.encodeToJsonElement(serializerOf(parameter.type.kType), call.parameters[i]))
            }
        }
        val text = when (val reply = rig.deviceVerb(verb, body = body.toString())) {
            is Reply.Done -> reply.body
            is Reply.Refused -> throw LeverRefused(verb, reply.reason)
            is Reply.Failed -> error("$verb answered ${reply.status}: ${reply.body}")
        }
        @Suppress("UNCHECKED_CAST")
        return json.decodeFromString(serializerOf(callable.returnType.kType), text) as T
    }

    override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
        error("${call.callableName}: the channel's services answer once; none streams")

    private fun serializerOf(type: KType): KSerializer<Any?> = serializer(type)

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}

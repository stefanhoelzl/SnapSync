package app.snapsync.rig

import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverNeed
import app.snapsync.levers.LeverService
import app.snapsync.levers.ThisDevice
import kotlinx.rpc.descriptor.RpcCallable
import kotlinx.rpc.descriptor.RpcInvokator
import kotlinx.rpc.descriptor.RpcParameter
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

// The typed services' verbs (`docs/testing.md`, "Typed services"): every method of every declared service
// (`LEVER_SERVICES`) is the `/device` verb `<service>/<method-in-kebab>`, dispatched onto the service's implementation
// through the descriptor the kotlinx-rpc compiler plugin generates — nothing here names a method. The same verb answers
// curl (arguments in the query, `assets=A,B` for a list) and the typed client in `:test:control` (arguments as one JSON
// object in the body, each encoded by its parameter's serializer); the reply is the return value, JSON-encoded.

/** The implementation of each service this world can serve — `null` where it has none (a need is unmet). */
internal fun MockWorld.serviceImplementation(service: LeverService<*>): Any? = when (service.name) {
    "backend" -> device.backend.operator.takeIf { isMocked(app.snapsync.mock.MockedSystem.BACKEND) }
    "public-backend" -> reach?.let { PublicBackend(this, it) }
    else -> null
}

/** Every declared service's verbs as levers, each needing what its service declares. */
internal fun MockWorld.serviceLevers(): Map<String, Lever> = LEVER_SERVICES.flatMap { service ->
    val needs = service.needs.map(::needOf)
    service.verbs.map { (method, verb) -> verb to Lever(needs, serviceCommand(service, method)) }
}.toMap()

/** The `/device` verbs of every declared service — what [RigVocabulary] lists for them. */
internal val SERVICE_VERBS: List<String> = LEVER_SERVICES.flatMap { it.verbs.values }.map { "device/$it" }

private fun needOf(need: LeverNeed): Need = when (need) {
    LeverNeed.BACKEND_MOCKED -> Need.Operator
    LeverNeed.BACKEND_REACHABLE -> Need.Reach
}

@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.serviceCommand(
    service: LeverService<*>,
    method: String,
): RigCommand = RigCommand { params, body ->
    @Suppress("UNCHECKED_CAST")
    val typed = service as LeverService<Any>
    val callable = typed.descriptor.getCallable(method) ?: error("$service declares no $method")
    val implementation = serviceImplementation(service) ?: error("$service has no implementation in this world")
    val arguments = try {
        argumentsOf(callable, params, body)
    } catch (bad: BadArgument) {
        return@RigCommand CommandResult.badRequest(bad.why)
    }
    val invoker = callable.invokator as RpcInvokator.Method<Any>
    val result = try {
        invoker.call(implementation, arguments)
    } catch (refused: IllegalArgumentException) {
        return@RigCommand CommandResult.badRequest(refused.message ?: "refused")
    }
    CommandResult.ok(leverJson.encodeToString(serializerOf(callable.returnType.kType), result))
}

/** An argument the method cannot take as sent — a `400` naming what was wrong. */
private class BadArgument(val why: String, cause: Throwable? = null) : Exception(why, cause)

/**
 * The call's arguments, in declaration order: from the body's JSON object when one was sent, else from the query text;
 * an omitted [JoinedEvent] or [ThisDevice] resolved here; any other omitted one `null`, or a `400` when it is not
 * nullable.
 */
@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.argumentsOf(callable: RpcCallable<*>, query: Map<String, String>, body: String?): Array<Any?> {
    val sent = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { leverJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
            ?: throw BadArgument("the body must be a JSON object of the arguments, by name")
    }
    val names = callable.parameters.map { it.name }
    val unknown = (query.keys + sent?.keys.orEmpty()) - names.toSet()
    if (unknown.isNotEmpty()) {
        throw BadArgument(
            "${callable.name} takes ${names.joinToString().ifEmpty { "no arguments" }}; unknown: ${unknown.sorted()}",
        )
    }
    return callable.parameters.map {
        argumentOf(
            it,
            sent?.get(it.name)?.takeUnless { e -> e is JsonNull },
            query[it.name],
        )
    }
        .toTypedArray()
}

/** One argument: [sent] in the body, else [text] in the query, else omitted. */
@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.argumentOf(parameter: RpcParameter, sent: JsonElement?, text: String?): Any? {
    val serializer = serializerOf(parameter.type.kType)
    val element = sent ?: text?.let {
        fromQuery(it, serializer.descriptor) ?: throw BadArgument("${parameter.name}: '$it' is no ${accepted(serializer.descriptor)}")
    }
    if (element == null) return omitted(parameter, serializer.descriptor.isNullable)
    return try {
        leverJson.decodeFromJsonElement(serializer, element)
    } catch (bad: SerializationException) {
        throw BadArgument(
            "${parameter.name}: ${bad.message?.lineSequence()?.first()} — accepted: ${accepted(serializer.descriptor)}",
            bad,
        )
    }
}

/** An omitted argument: the joined event or this device where marked, else `null` — or a `400` where it may not be. */
@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.omitted(parameter: RpcParameter, nullable: Boolean): Any? {
    val resolved = resolveOmitted(parameter)
    if (resolved == null && parameter.annotations.any { it is JoinedEvent }) {
        throw BadArgument("no joined event, and no `event` was named")
    }
    if (resolved == null && !nullable) throw BadArgument("${parameter.name} is required")
    return resolved
}

/** What an omitted [JoinedEvent] or [ThisDevice] names here, or `null`. */
@OptIn(ExperimentalRpcApi::class)
private fun MockWorld.resolveOmitted(parameter: RpcParameter): String? = when {
    parameter.annotations.any { it is JoinedEvent } -> joinedEventId()
    parameter.annotations.any { it is ThisDevice } -> ownDeviceId()
    else -> null
}

/** Query text as the JSON the parameter's serializer reads — `null` when it cannot be. */
private fun fromQuery(text: String, descriptor: SerialDescriptor): JsonElement? = when (descriptor.kind) {
    PrimitiveKind.STRING, SerialKind.ENUM -> JsonPrimitive(text)
    StructureKind.LIST -> JsonArray(text.split(',').filter { it.isNotBlank() }.map(::JsonPrimitive))
    is PrimitiveKind -> runCatching { leverJson.parseToJsonElement(text) }.getOrNull() as? JsonPrimitive
    else -> runCatching { leverJson.parseToJsonElement(text) }.getOrNull()
}

/** What a parameter accepts, for a `400`: an enum's values, or its kind. */
private fun accepted(descriptor: SerialDescriptor): String = when (descriptor.kind) {
    SerialKind.ENUM -> descriptor.elementNames.joinToString("|")
    else -> descriptor.serialName.removeSuffix("?").substringAfterLast('.')
}

private fun serializerOf(type: kotlin.reflect.KType): KSerializer<Any?> = serializer(type)

private val leverJson = Json { encodeDefaults = true }

package app.snapsync.cli

import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverService
import app.snapsync.levers.ThisDevice
import kotlinx.rpc.descriptor.RpcCallable
import kotlinx.rpc.descriptor.RpcParameter
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer

// The command set, DERIVED: every service `:test:levers` declares (`LEVER_SERVICES`) is a command group, and every
// callable its kotlinx-rpc descriptor names is a command — its parameters the flags, their types how each flag's text
// decodes. Nothing here lists a service or a method.

/** A service as a group of commands: `rig <service> <method> …`. */
internal class ServiceCommands(val service: LeverService<*>) {
    val name: String get() = service.name

    val methods: List<MethodCommand> = service.descriptor.callables.values.map { MethodCommand(service, it) }

    /** A method by its declared name (`failListing`) or its spelling as a command (`fail-listing`). */
    fun method(name: String): MethodCommand? = methods.firstOrNull { it.name == name || it.declared == name }
}

/** One method of a service: `rig <service> <name> [--<parameter> <value>]…`. */
internal class MethodCommand(val service: LeverService<*>, val callable: RpcCallable<*>) {
    /** The method's name as the interface declares it, and as a host's advertisement names it. */
    val declared: String get() = callable.name

    /** The method's name as a command. */
    val name: String get() = commandSpelling(declared)

    val parameters: List<ParameterFlag> = callable.parameters.map(::ParameterFlag)
}

/** A parameter as a flag: `--<name> <value>`. */
internal class ParameterFlag(val parameter: RpcParameter) {
    val declared: String get() = parameter.name
    val name: String get() = commandSpelling(declared)

    /** An omittable parameter is nullable; omitted, it arrives as `null` and the host says what that means. */
    val omittable: Boolean get() = parameter.type.kType.isMarkedNullable

    /** What decodes the flag's text: the parameter type's own serializer, as kRPC encodes it. */
    val descriptor: SerialDescriptor by lazy { serializer(parameter.type.kType).descriptor }

    /** A list parameter takes the flag more than once, or its elements comma-separated. */
    val repeatable: Boolean get() = descriptor.kind == StructureKind.LIST

    /** A `Boolean` flag may stand alone: `--on` is `--on true`. */
    val switch: Boolean get() = descriptor.valueKind() == PrimitiveKind.BOOLEAN

    /** What an omitted value means, when the host resolves it. */
    val omittedMeans: String? get() = when {
        parameter.annotations.any { it is JoinedEvent } -> "the joined event"
        parameter.annotations.any { it is ThisDevice } -> "this device"
        omittable -> "none"
        else -> null
    }

    /** The value's type as help shows it: `String`, `Int`, or an enum's values; a list's with `…`. */
    val typeLabel: String get() {
        val value = (if (repeatable) descriptor.getElementDescriptor(0) else descriptor).valueDescriptor()
        val label = if (value.kind == SerialKind.ENUM) {
            value.elementNames.joinToString("|") { commandSpelling(it) }
        } else {
            value.serialName.removeSuffix("?").substringAfterLast('.')
        }
        return if (repeatable) "$label…" else label
    }
}

/** Every service the channel declares, as commands. */
internal val SERVICE_COMMANDS: List<ServiceCommands> by lazy { LEVER_SERVICES.map(::ServiceCommands) }

internal fun serviceCommands(name: String): ServiceCommands? = SERVICE_COMMANDS.firstOrNull { it.name == name }

/** A Kotlin name as a command spells it: `failListing` → `fail-listing`, `SERVER_ERROR` → `server-error`. */
internal fun commandSpelling(name: String): String =
    if (name.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
        name.lowercase().replace('_', '-')
    } else {
        name.replace(Regex("(?<=[a-z0-9])([A-Z])")) { "-${it.value}" }.lowercase()
    }

/** A value class's descriptor is its underlying value's: it encodes as that value alone. */
internal fun SerialDescriptor.valueDescriptor(): SerialDescriptor = if (isInline) getElementDescriptor(0) else this

internal fun SerialDescriptor.valueKind(): SerialKind = valueDescriptor().kind

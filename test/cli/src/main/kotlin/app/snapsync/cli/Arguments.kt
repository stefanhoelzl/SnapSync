package app.snapsync.cli

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

// A command's flags, decoded into the method's arguments. Each flag's text becomes the JSON its parameter's serializer
// reads — by the descriptor's kind, so an enum, a value class or a list decodes by the same rule as a `String` — and
// the serializer builds the argument. An omitted nullable parameter is `null`, which the host resolves or the method
// gives its meaning; there is no default on this side.

/** A command line the CLI could not make into a call: what was wrong, and the help that says what is right. */
internal class UsageError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The command line is wrong: [message] says how. */
internal fun usage(message: String): Nothing = throw UsageError(message)

/** The method's arguments, in declaration order, from its flags. */
internal fun MethodCommand.arguments(flags: List<String>): Array<Any?> {
    val given = parseFlags(flags)
    val unknown = given.keys.filter { key -> parameters.none { it.name == key || it.declared == key } }
    if (unknown.isNotEmpty()) {
        usage("${service.name} $name: no parameter ${unknown.joinToString { "--$it" }}${expected()}")
    }
    return parameters.map { flag ->
        val values = given[flag.name].orEmpty() + given[flag.declared].takeIf { flag.declared != flag.name }.orEmpty()
        when {
            values.isEmpty() && flag.omittable -> null
            values.isEmpty() -> usage("${service.name} $name: --${flag.name} is required${expected()}")
            values.size > 1 && !flag.repeatable -> usage("${service.name} $name: --${flag.name} given twice")
            else -> flag.decode(values)
        }
    }.toTypedArray()
}

private fun MethodCommand.expected(): String = " — usage: ${usage()}"

/** `--name value`, `--name=value`, and a bare `--name` (a switch: `true`); each name to every value it was given. */
private fun MethodCommand.parseFlags(flags: List<String>): Map<String, List<String?>> {
    val given = linkedMapOf<String, MutableList<String?>>()
    var i = 0
    while (i < flags.size) {
        val token = flags[i]
        if (!token.startsWith("--") || token.length == 2) {
            usage("${service.name} $name: expected a --flag, got '$token'${expected()}")
        }
        val (key, inline) = token.removePrefix("--").split('=', limit = 2).let { it[0] to it.getOrNull(1) }
        val flag = parameters.firstOrNull { it.name == key || it.declared == key }
        val next = flags.getOrNull(i + 1)
        val value = when {
            inline != null -> inline
            flag?.switch == true && (next == null || next.startsWith("--")) -> null
            next == null || next.startsWith("--") -> usage("${service.name} $name: --$key needs a value")
            else -> next.also { i++ }
        }
        given.getOrPut(key) { mutableListOf() }.add(value)
        i++
    }
    return given
}

private fun ParameterFlag.decode(values: List<String?>): Any? {
    val element = if (repeatable) {
        val itemDescriptor = descriptor.getElementDescriptor(0)
        val item = itemDescriptor.valueDescriptor()
        if (item.kind is PrimitiveKind || item.kind == SerialKind.ENUM) {
            JsonArray(
                values.flatMap { it.orEmpty().split(',') }.filter { it.isNotEmpty() }.map { json(itemDescriptor, it) },
            )
        } else {
            // A structure's JSON holds commas of its own: each flag is one item, or the whole list as a JSON array.
            JsonArray(
                values.map { it.orEmpty().trim() }.filter { it.isNotEmpty() }.flatMap { text ->
                    if (text.startsWith(
                            '[',
                        )
                    ) {
                        Json.parseToJsonElement(text) as JsonArray
                    } else {
                        listOf(json(itemDescriptor, text))
                    }
                },
            )
        }
    } else {
        values.single()?.let { json(descriptor, it) } ?: JsonPrimitive(true)
    }
    return try {
        Json.decodeFromJsonElement(serializer(parameter.type.kType), element)
    } catch (e: IllegalArgumentException) {
        throw UsageError(
            "--$name: ${values.joinToString(",")} is no $typeLabel (${e.message?.lineSequence()?.first()})",
            e,
        )
    }
}

/** A flag's text as the JSON [descriptor] reads. */
private fun ParameterFlag.json(descriptor: SerialDescriptor, text: String): JsonElement {
    val value = descriptor.valueDescriptor()
    fun bad(): Nothing = usage("--$name: '$text' is no ${typeLabel.removeSuffix("…")}")
    return when (value.kind) {
        PrimitiveKind.BOOLEAN -> JsonPrimitive(text.toBooleanStrictOrNull() ?: bad())
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
            JsonPrimitive(text.toLongOrNull() ?: bad())
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(text.toDoubleOrNull() ?: bad())
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive(text)
        SerialKind.ENUM -> JsonPrimitive(value.elementNames.firstOrNull { matches(it, text) } ?: bad())
        StructureKind.LIST -> bad()
        // Anything else — a structure no service takes today — is written as its JSON.
        else -> if (text == "null") JsonNull else Json.parseToJsonElement(text)
    }
}

/** An enum value by its serial name or its command spelling, in any case: `maintenance`, `MAINTENANCE`, `server-error`. */
private fun matches(serialName: String, text: String): Boolean =
    serialName.equals(text, ignoreCase = true) || commandSpelling(serialName) == text.lowercase().replace('_', '-')

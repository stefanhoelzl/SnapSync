package app.snapsync.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val everyField = Json { encodeDefaults = true }

/** The library's default writer, which leaves out a field at its default. */
private val omitting = Json

/**
 * Which fields of a `@Serializable` type a document may leave out — the shape an older writer, a newer one, or the
 * other end of a wire is held to.
 *
 * [full] (every field away from its default, so a decoder that ignored one would be caught) round-trips, compared as
 * documents so a type without value equality is held to it too. Then each
 * field is dropped in turn: a field named in [optional] decodes to its default and leaves every other field as it
 * was, and any other field is required, so the document without it is refused rather than read as something else.
 * The set of optional fields is asserted exactly: a field that gains or loses a default changes what this type
 * accepts, and that is a decision, not an accident. A writer that omits defaults leaves out exactly the fields at
 * their default: none of [full]'s, and every optional one of the value read from a document without them.
 */
internal fun <T> assertFieldContract(serializer: KSerializer<T>, full: T, optional: Set<String> = emptySet()) {
    val encoded = everyField.encodeToJsonElement(serializer, full).jsonObject
    val name = serializer.descriptor.serialName
    assertEquals(
        encoded,
        everyField.encodeToJsonElement(serializer, everyField.decodeFromJsonElement(serializer, encoded)),
        "$name round-trips",
    )
    assertEquals(optional, optional.intersect(encoded.keys), "every optional field is encoded")
    // This type's own fields: a nested value may hold its own defaults, and its own contract covers them.
    assertEquals(
        encoded.keys,
        omitting.encodeToJsonElement(serializer, full).jsonObject.keys,
        "$name: no field is at its default",
    )
    val bare = JsonObject(encoded - optional)
    val defaults = everyField.decodeFromJsonElement(serializer, bare)
    assertEquals(
        bare.keys,
        omitting.encodeToJsonElement(serializer, defaults).jsonObject.keys,
        "$name: every default is dropped",
    )
    for (key in encoded.keys) {
        val without = JsonObject(encoded - key)
        if (key in optional) {
            val decoded = everyField.decodeFromJsonElement(serializer, without)
            val others = JsonObject(everyField.encodeToJsonElement(serializer, decoded).jsonObject - key)
            assertEquals(without, others, "dropping `$key` changes only `$key`")
        } else {
            assertFailsWith<SerializationException>("`$key` is required") {
                everyField.decodeFromJsonElement(serializer, without)
            }
        }
    }
}

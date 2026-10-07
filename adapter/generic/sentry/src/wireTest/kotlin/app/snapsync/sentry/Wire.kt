package app.snapsync.sentry

import app.snapsync.contracts.DeliveredEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// What the `CrashReporter` contract's loopback ingests know of Sentry's wire format, compiled into BOTH platforms'
// bindings (`iosTest`, `androidDeviceTest`), so the two read an envelope the same way. Measured against sentry-cocoa
// 8.58.2 on 2026-09-23 (`changes/…/diagnostics-reporter-contracts/design.md`, M1–M2): the SDK POSTs one envelope per
// request with a `Content-Length`, gzip-encoded.

/** The DSN key the ingests are addressed with. A fixed non-secret: nothing checks it. */
internal const val INGEST_PUBLIC_KEY = "cafebabecafebabecafebabecafebabe"

/** The DSN that routes the SDK to a loopback ingest on [port]. */
internal fun ingestDsn(port: Int): String = "http://$INGEST_PUBLIC_KEY@127.0.0.1:$port/1"

/**
 * Bugsink's `MAX_EVENT_SIZE`, measured 2026-07-29: larger events are refused with a `413`. An ingest refuses the same,
 * judged on the DECODED body (compression hides the size, M8), so no clause passes with a payload the real ingest
 * would refuse.
 */
internal const val MAX_EVENT_SIZE = 1024 * 1024

/**
 * The `event` items of one envelope. An envelope is newline-delimited: an envelope header, then for each item an item
 * header and its payload. A payload whose item header names a `length` is exactly that many bytes; otherwise it runs to
 * the next newline. Only `event` items are kept — the SDKs also send `client_report` and `session` items (M2).
 */
internal fun envelopeEvents(envelope: ByteArray): List<JsonObject> {
    val events = mutableListOf<JsonObject>()
    var at = lineEnd(envelope, 0) + 1 // skip the envelope header
    while (at < envelope.size) {
        val headerEnd = lineEnd(envelope, at)
        val itemHeader = envelope.copyOfRange(at, headerEnd).decodeToString()
        if (itemHeader.isBlank()) break
        val header = Json.parseToJsonElement(itemHeader).jsonObject
        val start = headerEnd + 1
        val end = header["length"]?.jsonPrimitive?.int?.let { start + it } ?: lineEnd(envelope, start)
        if (header["type"]?.jsonPrimitive?.content == "event") {
            events += Json.parseToJsonElement(envelope.copyOfRange(start, end).decodeToString()).jsonObject
        }
        at = end + 1
    }
    return events
}

private fun lineEnd(bytes: ByteArray, from: Int): Int {
    var i = from
    while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
    return i
}

/** The wire format onto the contract's vocabulary: the one place that knows Sentry's event shape. */
internal fun JsonObject.toDelivered(): DeliveredEvent {
    val message = this["message"]?.let { m ->
        when (m) {
            // sentry-java sends the message as an object, as sentry-cocoa does; a bare string is also legal.
            is JsonObject -> (m["formatted"] ?: m["message"])?.jsonPrimitive?.content
            is JsonPrimitive -> m.content
            else -> null
        }
    }
    // An event's breadcrumbs arrive as a bare array (measured); the protocol also allows `{ "values": [...] }`.
    val crumbs = values(this["breadcrumbs"]).mapNotNull { (it as? JsonObject)?.get("message")?.jsonPrimitive?.content }
    val tags = (this["tags"] as? JsonObject).orEmpty()
    // The SDK adds contexts of its own (device, os, app) whose values are not all strings; only the all-string ones are
    // ours to compare.
    val contexts = (this["contexts"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        val fields = (value as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }
        fields?.takeIf { f -> f.values.all { it != null } }?.let { f -> name to f.mapValues { it.value!! } }
    }.toMap()
    return DeliveredEvent(
        message = message,
        breadcrumbs = crumbs,
        installId = (this["user"] as? JsonObject)?.get("id")?.jsonPrimitive?.content,
        tags = tags.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap(),
        contexts = contexts,
        hasException = values(this["exception"]).isNotEmpty(),
        release = (this["release"] as? JsonPrimitive)?.content,
        environment = (this["environment"] as? JsonPrimitive)?.content,
        dist = (this["dist"] as? JsonPrimitive)?.content,
    )
}

/** The protocol allows a list either bare or as `{ "values": [...] }`. */
private fun values(
    raw: kotlinx.serialization.json.JsonElement?,
): List<kotlinx.serialization.json.JsonElement> = when (raw) {
    is JsonArray -> raw
    is JsonObject -> raw["values"] as? JsonArray
    else -> null
}.orEmpty()

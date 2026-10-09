package app.snapsync.buildlogic.wire

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * `api/openapi.yaml`, read into what the app's half of the wire is generated from: each operation the app calls or
 * builds, its inputs, and — for every declared response — the OUTCOME it names (`x-snapsync-outcome`) and what that
 * outcome carries. The backend's half reads the same description (`api/src/scripts/generate-wire.ts`).
 *
 * It REFUSES rather than guesses ([WireDescriptionException]): a response with no outcome, a payload shape it does
 * not translate, an `x-snapsync-*` name outside the closed vocabulary — each fails the build naming the place.
 */
class WireDescription private constructor(private val api: OpenAPI) {

    private val reader = WireResponses(api)

    /** The outcomes defined once under `components/responses` — each ONE type on every route that references it. */
    val sharedOutcomes: Map<String, WireOutcome> = reader.shared

    /** The operations, in description order. */
    val operations: List<WireOperation> = readOperations()

    /** The constants the app reads off schema constraints (`x-snapsync-export`): name → value. */
    val exports: Map<String, Int> = readExports()

    /** The header every request carries for the version gate (`x-snapsync-gate: version`). */
    val versionHeader: String = api.components.parameters.values
        .single { it.extensions?.get("x-snapsync-gate") == "version" }.name

    private fun readOperations(): List<WireOperation> = api.paths.flatMap { (path, item) ->
        item.readOperationsMap().map { (method, op) -> operation(path, item, method, op) }
    }

    private fun operation(path: String, item: PathItem, method: PathItem.HttpMethod, op: Operation): WireOperation {
        val id = op.operationId ?: fail("$method $path", "has no operationId")
        val params = (item.parameters.orEmpty() + op.parameters.orEmpty()).map(::resolveParameter)
            .filter { it.extensions?.get("x-snapsync-gate") == null }
            .map { WireParameter(it.name, it.`in`, it.required == true, isBoolean(it.schema)) }
        val transport = op.extensions?.get("x-snapsync-transport") as String?
        return WireOperation(
            id = id,
            method = method.name,
            path = path,
            gated = isGated(op),
            os = transport == "os",
            parameters = params,
            body = requestBodyType(id, op),
            responses = if (transport == "os") emptyMap() else responses(id, op),
        )
    }

    /** Gated unless the operation's `security` is empty — the legacy optional reads are sent as gated. */
    private fun isGated(op: Operation): Boolean = (op.security ?: api.security).orEmpty().isNotEmpty()

    private fun requestBodyType(id: String, op: Operation): String? =
        op.requestBody?.content?.get("application/json")?.let { json ->
            refName(json.schema?.`$ref` ?: fail(id, "a JSON request body is a component schema"))
        }

    private fun responses(id: String, op: Operation): Map<Int, WireBranch> =
        op.responses.entries.associate { (code, raw) ->
            val status = code.toIntOrNull() ?: fail(id, "response `$code` is not one status code")
            status to reader.branch("$id $code", status, raw)
        }

    private fun readExports(): Map<String, Int> = api.components.schemas.values.mapNotNull { schema ->
        val name = schema.extensions?.get("x-snapsync-export") as String? ?: return@mapNotNull null
        name to (schema.maxLength ?: fail(name, "only a `maxLength` is exported"))
    }.toMap()

    private fun resolveParameter(p: Parameter): Parameter =
        p.`$ref`?.let { api.components.parameters[it.substringAfterLast('/')] } ?: p

    private fun isBoolean(schema: Schema<*>?): Boolean = schema?.typeSet()?.contains("boolean") == true

    companion object {
        /** The closed `x-snapsync-*` vocabulary (`api/openapi.yaml`, "Conventions"). */
        val VOCABULARY = setOf(
            "x-snapsync-outcome", "x-snapsync-instant", "x-snapsync-after", "x-snapsync-max-span",
            "x-snapsync-check", "x-snapsync-gate", "x-snapsync-export", "x-snapsync-transport",
            "x-snapsync-refusals", "x-snapsync-on-invalid", "x-snapsync-trim",
        )

        /** Reads [text], refusing a description the parser reports problems in or that names an unknown extension. */
        fun read(text: String): WireDescription {
            val unknown = Regex("""\b(x-snapsync-[a-z-]+)\s*:""").findAll(text).map { it.groupValues[1] }
                .filterNot { it in VOCABULARY }.toSet()
            if (unknown.isNotEmpty()) fail("openapi.yaml", "names $unknown, outside the closed x-snapsync vocabulary")
            val result = OpenAPIV3Parser().readContents(text, null, ParseOptions().apply { isResolve = false })
            val api = result.openAPI ?: fail("openapi.yaml", result.messages.joinToString("; "))
            if (result.messages.isNotEmpty()) fail("openapi.yaml", result.messages.joinToString("; "))
            return WireDescription(api)
        }

        private val SUCCESS = 200..399
        private val CLIENT_ERROR = 400..499
        private val SERVER_ISSUE = 500..599

        fun groupOf(status: Int): String = when (status) {
            in SUCCESS -> "Success"
            in CLIENT_ERROR -> "ClientError"
            in SERVER_ISSUE -> "ServerIssue"
            else -> fail("status $status", "belongs to no outcome group")
        }
    }
}

private fun Schema<*>.typeSet(): Set<String> = types ?: setOfNotNull(type)

/** The component a schema reference names; a reference anywhere else is refused. */
internal fun refName(ref: String): String {
    if (!ref.startsWith("#/components/schemas/")) fail(ref, "only component schemas are referenced")
    return ref.substringAfterLast('/')
}

/** How each declared response becomes an outcome — the half of [WireDescription] that reads `responses`. */
private class WireResponses(private val api: OpenAPI) {

    fun branch(where: String, status: Int, raw: ApiResponse): WireBranch {
        raw.`$ref`?.let { ref ->
            val name = ref.substringAfterLast('/')
            val outcome = shared[name] ?: fail(where, "references `$ref`, which names no outcome")
            if (outcome.group != WireDescription.groupOf(status)) {
                fail(
                    where,
                    "`$name` is a ${outcome.group}, not at $status",
                )
            }
            return WireBranch.Single(outcome)
        }
        return responseBranch(where, status, raw, shared = false)
    }

    /**
     * One response's outcome: by media type when it declares several; else the outcome its media type or the
     * response names; else, for a text body that is an annotated enumeration, one outcome per alternative.
     */
    private fun responseBranch(where: String, status: Int, response: ApiResponse, shared: Boolean): WireBranch {
        val headers = responseHeaders(response)
        val content = response.content.orEmpty()
        val group = WireDescription.groupOf(status)
        val (type, media) = content.entries.singleOrNull()?.toPair() ?: (null to null)
        val named = media?.extensions?.get("x-snapsync-outcome") as String?
            ?: response.extensions?.get("x-snapsync-outcome") as String?
        return when {
            content.size > 1 -> WireBranch.ByMedia(
                content.map { (t, m) ->
                    val name = m.extensions?.get("x-snapsync-outcome") as String?
                        ?: fail(where, "media type `$t` names no outcome")
                    t to WireOutcome(name, group, shared, payload(where, t, m), headers)
                },
            )
            named != null -> WireBranch.Single(WireOutcome(named, group, shared, payload(where, type, media), headers))
            else -> WireBranch.ByText(
                (media?.schema?.oneOf ?: fail(where, "names no outcome")).map { textMember(where, status, it) },
            )
        }
    }

    private fun textMember(where: String, status: Int, member: Schema<*>): Pair<WireMatch, WireOutcome> {
        val name = member.extensions?.get("x-snapsync-outcome") as String?
            ?: fail(where, "a body alternative names no outcome")
        val match = when {
            member.const != null -> WireMatch.Exactly(listOf(member.const.toString()))
            member.enum != null -> WireMatch.Exactly(member.enum.map { it.toString() })
            member.pattern != null -> WireMatch.Pattern(member.pattern)
            else -> fail(where, "body alternative `$name` is neither a const, an enum nor a pattern")
        }
        val payload = if (match is WireMatch.Pattern) WirePayload.Text else WirePayload.None
        return match to WireOutcome(name, WireDescription.groupOf(status), shared = false, payload, emptyList())
    }

    private fun payload(where: String, type: String?, media: MediaType?): WirePayload {
        val schema = media?.schema ?: return WirePayload.None
        return when (type) {
            "application/json" -> when {
                schema.`$ref` != null -> WirePayload.Json(refName(schema.`$ref`))
                schema.items?.`$ref` != null -> WirePayload.JsonList(refName(schema.items.`$ref`))
                else -> fail(where, "a JSON body is a component schema or a list of one")
            }
            "text/plain" -> if (schema.const != null || schema.enum != null) WirePayload.None else WirePayload.Text
            else -> WirePayload.None
        }
    }

    private fun responseHeaders(response: ApiResponse): List<WireHeader> =
        response.headers.orEmpty().map { (name, raw) ->
            val header = raw.`$ref`?.let { api.components.headers[it.substringAfterLast('/')] } ?: raw
            WireHeader(name, header.required == true)
        }

    /** The outcomes defined once under `components/responses`. */
    val shared: Map<String, WireOutcome> =
        api.components.responses.orEmpty().mapValues { (name, response) ->
            val status = sharedStatus(name)
            when (val branch = responseBranch("#/components/responses/$name", status, response, shared = true)) {
                is WireBranch.Single -> branch.outcome
                else -> fail(name, "a shared response names ONE outcome")
            }
        }

    /** The status a shared response is used at — one, or the outcome would belong to two groups. */
    private fun sharedStatus(name: String): Int {
        val used = api.paths.values.flatMap { it.readOperations() }.flatMap { op ->
            op.responses.filter { (_, r) -> r.`$ref`?.substringAfterLast('/') == name }.keys
        }.map { it.toInt() }.toSet()
        val groups = used.map(WireDescription::groupOf).toSet()
        if (groups.size != 1) fail("#/components/responses/$name", "is used in ${groups.size} status groups")
        return used.first()
    }
}

/** What the description says is wrong, naming where. */
class WireDescriptionException(message: String) : Exception(message)

internal fun fail(where: String, why: String): Nothing = throw WireDescriptionException("openapi.yaml $where: $why")

/** One operation: how it is reached, what it takes, and — for one the app sends itself — its outcomes by status. */
data class WireOperation(
    val id: String,
    val method: String,
    val path: String,
    val gated: Boolean,
    /** Built by the app, performed by the OS transfer services: no reply is decoded. */
    val os: Boolean,
    val parameters: List<WireParameter>,
    /** The JSON request body's component schema, or `null`. */
    val body: String?,
    val responses: Map<Int, WireBranch>,
)

data class WireParameter(val name: String, val location: String, val required: Boolean, val boolean: Boolean)

data class WireHeader(val name: String, val required: Boolean)

/** What an outcome carries: nothing, the body's text, or a decoded JSON body. */
sealed interface WirePayload {
    data object None : WirePayload
    data object Text : WirePayload
    data class Json(val schema: String) : WirePayload
    data class JsonList(val schema: String) : WirePayload
}

data class WireOutcome(
    val name: String,
    val group: String,
    val shared: Boolean,
    val payload: WirePayload,
    val headers: List<WireHeader>,
)

/** How one status's answer becomes an outcome. */
sealed interface WireBranch {
    data class Single(val outcome: WireOutcome) : WireBranch
    data class ByMedia(val outcomes: List<Pair<String, WireOutcome>>) : WireBranch
    data class ByText(val outcomes: List<Pair<WireMatch, WireOutcome>>) : WireBranch
}

sealed interface WireMatch {
    data class Exactly(val texts: List<String>) : WireMatch
    data class Pattern(val regex: String) : WireMatch
}

package app.snapsync.buildlogic.wire

import app.snapsync.buildlogic.wire.WireRoutes.GATED
import app.snapsync.buildlogic.wire.WireRoutes.GENERATED
import app.snapsync.buildlogic.wire.WireRoutes.MALFORMED
import app.snapsync.buildlogic.wire.WireRoutes.MODELS
import app.snapsync.buildlogic.wire.WireRoutes.OPEN
import app.snapsync.buildlogic.wire.WireRoutes.PACKAGE
import app.snapsync.buildlogic.wire.WireRoutes.RUNTIME
import app.snapsync.buildlogic.wire.WireRoutes.WIRE_REQUEST
import app.snapsync.buildlogic.wire.WireRoutes.WIRE_RESPONSE
import app.snapsync.buildlogic.wire.WireRoutes.fields
import app.snapsync.buildlogic.wire.WireRoutes.replyName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName

/** `Routes.kt`: one object per operation — its path, and for one the app sends, its typed request and decoder. */
internal object WireRouteObjects {

    fun routes(d: WireDescription): FileSpec {
        val routes = TypeSpec.objectBuilder("Routes")
            .addKdoc("Every operation of `api/openapi.yaml` the app calls or builds, by its operationId.")
        for (op in d.operations.filter { it.method != "OPTIONS" }) routes.addType(route(d, op))
        return FileSpec.builder(PACKAGE, "Routes").addFileComment(GENERATED)
            .addImport(RUNTIME, "encodeSegment", "query")
            .addImport("kotlinx.serialization.builtins", "ListSerializer")
            .addType(routes.build()).build()
    }

    private fun route(d: WireDescription, op: WireOperation): TypeSpec {
        val type = TypeSpec.objectBuilder(op.id.capitalized())
            .addKdoc("`${op.method} ${op.path}`${if (op.os) " — performed by the OS transfer services." else "."}")
            .addProperty(
                PropertySpec.builder("TEMPLATE", String::class, KModifier.CONST).initializer("%S", op.path).build(),
            )
            .addProperty(
                PropertySpec.builder("METHOD", String::class, KModifier.CONST).initializer("%S", op.method).build(),
            )
        op.parameters.filter { it.location == "header" }.forEach { h ->
            type.addProperty(
                PropertySpec.builder("HEADER_${h.name.constant()}", String::class, KModifier.CONST)
                    .initializer("%S", h.name).build(),
            )
        }
        type.addFunction(pathFunction(op))
        if (!op.os) {
            type.addFunction(requestFunction(op))
            type.addFunction(decideFunction(d, op))
        }
        return type.build()
    }

    private fun parameterSpec(p: WireParameter): ParameterSpec {
        val type = (if (p.boolean) Boolean::class else String::class).asTypeName()
        return if (p.location == "path") {
            ParameterSpec.builder(p.name.identifier(), type).build()
        } else {
            ParameterSpec.builder(p.name.identifier(), type.copy(nullable = true)).defaultValue("null").build()
        }
    }

    private fun pathFunction(op: WireOperation): FunSpec {
        val params = op.pathParameters + op.queryParameters
        val path = CodeBlock.builder()
        Regex("""\{(\w+)}|[^{]+""").findAll(op.path).forEachIndexed { i, m ->
            if (i > 0) path.add(" + ")
            if (m.groupValues[1].isNotEmpty()) {
                path.add("encodeSegment(%L)", m.groupValues[1].identifier())
            } else {
                path.add("%S", m.value)
            }
        }
        val query = op.queryParameters
        if (query.isNotEmpty()) {
            path.add(" + query(")
            query.forEachIndexed { i, q ->
                if (i > 0) path.add(", ")
                path.add("%S to %L%L", q.name, q.name.identifier(), if (q.boolean) "?.toString()" else "")
            }
            path.add(")")
        }
        return FunSpec.builder("path").addKdoc("The path, relative to the device API's base, with its query.")
            .addParameters(params.map(::parameterSpec)).returns(String::class).addCode("return %L\n", path.build())
            .build()
    }

    private fun requestFunction(op: WireOperation): FunSpec {
        val reply = replyName(op)
        val request = if (op.gated) GATED else OPEN
        val fn = FunSpec.builder("request")
            .addParameters((op.pathParameters + op.queryParameters + op.headerParameters).map(::parameterSpec))
            .returns(request.parameterizedBy(reply))
        op.body?.let { fn.addParameter("body", ClassName(MODELS, it)) }
        val pathArgs = (op.pathParameters + op.queryParameters).joinToString(", ") { it.name.identifier() }
        val headers = CodeBlock.builder().add("buildMap {\n").indent()
        op.headerParameters.forEach { h ->
            headers.add("%L?.let { put(%S, it) }\n", h.name.identifier(), h.name)
        }
        if (op.body != null) headers.add("put(%S, %S)\n", "Content-Type", "application/json")
        headers.unindent().add("}")
        val body = op.body?.let {
            val json = ClassName(RUNTIME, "WireJson")
            CodeBlock.of("%T.encodeToString(%T.serializer(), body)", json, ClassName(MODELS, it))
        } ?: CodeBlock.of("null")
        return fn.addCode(
            "return %T(%T(%S, path(%L), %L, %L), ::decide)\n",
            request,
            WIRE_REQUEST,
            op.method,
            pathArgs,
            headers.build(),
            body,
        ).build()
    }

    private fun decideFunction(d: WireDescription, op: WireOperation): FunSpec {
        val reply = replyName(op)
        val code = CodeBlock.builder().add("return when (r.status) {\n").indent()
        for ((status, branch) in op.responses.toSortedMap()) {
            code.add("%L -> ", status)
            code.add(branchCode(d, op, branch))
            code.add("\n")
        }
        code.add("else -> %T(%P)\n", MALFORMED, "undeclared status \${r.status}")
        code.unindent().add("}\n")
        return FunSpec.builder("decide").addKdoc("The reply [r] is, by the description's declared responses.")
            .addParameter("r", WIRE_RESPONSE).returns(reply).addCode(code.build()).build()
    }

    private fun branchCode(d: WireDescription, op: WireOperation, branch: WireBranch): CodeBlock = when (branch) {
        is WireBranch.Single -> construct(d, op, branch.outcome)
        is WireBranch.ByMedia -> mediaBranch(d, op, branch)
        is WireBranch.ByText -> textBranch(d, op, branch)
    }

    /** One status, several media types: the answer's `Content-Type` picks the outcome. */
    private fun mediaBranch(d: WireDescription, op: WireOperation, branch: WireBranch.ByMedia): CodeBlock {
        val code = CodeBlock.builder().add("when {\n").indent()
        branch.outcomes.forEach { (type, outcome) ->
            code.add(
                "r.isMedia(%S) -> %L\n",
                type,
                construct(d, op, outcome),
            )
        }
        code.add("else -> %T(%P)\n", MALFORMED, "no declared media type for \${r.status}")
        return code.unindent().add("}").build()
    }

    /** One status, several texts: the body's text picks the outcome — an annotated enumeration of it. */
    private fun textBranch(d: WireDescription, op: WireOperation, branch: WireBranch.ByText): CodeBlock {
        val code = CodeBlock.builder().add("when {\n").indent()
        branch.outcomes.forEach { (match, outcome) ->
            code.add(
                "%L -> %L\n",
                condition(match),
                construct(d, op, outcome),
            )
        }
        code.add("else -> %T(%P)\n", MALFORMED, "an undeclared body for \${r.status}")
        return code.unindent().add("}").build()
    }

    /** The expression that builds [outcome] from `r`, or `Malformed` when what it carries does not decode. */
    private fun construct(d: WireDescription, op: WireOperation, outcome: WireOutcome): CodeBlock {
        val shared = d.sharedOutcomes[outcome.name]
        val target = if (shared != null) ClassName(PACKAGE, outcome.name) else replyName(op).nestedClass(outcome.name)
        val carried = shared ?: outcome
        val fields = fields(carried)
        if (fields.isEmpty()) return CodeBlock.of("%T", target)
        val code = CodeBlock.builder().add("run {\n").indent()
        val args = mutableListOf<String>()
        when (val p = carried.payload) {
            WirePayload.None -> Unit
            WirePayload.Text -> args += "r.body"
            is WirePayload.Json -> {
                code.add(
                    "val body = r.json(%T.serializer()) { return@run %T(%S + it) }\n",
                    ClassName(MODELS, p.schema),
                    MALFORMED,
                    "${op.id}: an undecodable ${outcome.name} — ",
                )
                args += "body"
            }
            is WirePayload.JsonList -> {
                code.add(
                    "val body = r.json(ListSerializer(%T.serializer())) { return@run %T(%S + it) }\n",
                    ClassName(MODELS, p.schema),
                    MALFORMED,
                    "${op.id}: an undecodable ${outcome.name} — ",
                )
                args += "body"
            }
        }
        carried.headers.forEach { h ->
            val name = h.name.identifier()
            if (h.required) {
                code.add(
                    "val %L = r.header(%S) ?: return@run %T(%S)\n",
                    name,
                    h.name,
                    MALFORMED,
                    "${op.id}: ${outcome.name} without `${h.name}`",
                )
            } else {
                code.add("val %L = r.header(%S)\n", name, h.name)
            }
            args += name
        }
        return code.add("%T(%L)\n", target, args.joinToString(", ")).unindent().add("}").build()
    }
}

/** The test that picks a text alternative: one of its texts exactly, or its pattern anywhere in the body. */
private fun condition(match: WireMatch): CodeBlock = when (match) {
    is WireMatch.Exactly ->
        CodeBlock.of(
            "r.body.trim() in setOf(%L)",
            match.texts.joinToString(", ") { CodeBlock.of("%S", it).toString() },
        )
    is WireMatch.Pattern -> CodeBlock.of("Regex(%S).containsMatchIn(r.body)", match.regex)
}

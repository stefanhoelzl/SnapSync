package app.snapsync.buildlogic.wire

import app.snapsync.buildlogic.wire.WireRoutes.GATED
import app.snapsync.buildlogic.wire.WireRoutes.GENERATED
import app.snapsync.buildlogic.wire.WireRoutes.GROUPS
import app.snapsync.buildlogic.wire.WireRoutes.MALFORMED
import app.snapsync.buildlogic.wire.WireRoutes.OPEN
import app.snapsync.buildlogic.wire.WireRoutes.PACKAGE
import app.snapsync.buildlogic.wire.WireRoutes.REPLY
import app.snapsync.buildlogic.wire.WireRoutes.UNREACHABLE
import app.snapsync.buildlogic.wire.WireRoutes.WIRE_REQUEST
import app.snapsync.buildlogic.wire.WireRoutes.WIRE_RESPONSE
import app.snapsync.buildlogic.wire.WireRoutes.fields
import app.snapsync.buildlogic.wire.WireRoutes.replyName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/** `Replies.kt`: the sealed `Reply` hierarchy — groups, shared outcomes, and one sealed reply per app operation. */
internal object WireReplies {

    fun replies(d: WireDescription): FileSpec {
        val file = FileSpec.builder(PACKAGE, "Replies").addFileComment(GENERATED)
        file.addType(
            TypeSpec.interfaceBuilder(REPLY).addModifiers(KModifier.SEALED)
                .addKdoc("What a backend operation answered, as the app reads it — one sealed type per operation.")
                .build(),
        )
        GROUPS.forEach { g ->
            file.addType(TypeSpec.interfaceBuilder(g).addModifiers(KModifier.SEALED).addSuperinterface(REPLY).build())
        }
        for (op in d.appOperations) file.addType(routeReply(d, op))
        for (outcome in d.sharedOutcomes.values) {
            val users = d.appOperations.filter { op -> op.outcomes().any { it.name == outcome.name } }
            file.addType(outcomeType(outcome, ClassName(PACKAGE, outcome.group), users.map(::replyName)))
        }
        val all = d.appOperations.map(::replyName)
        file.addType(fixedOutcome(UNREACHABLE, "reason", "No answer reached the app.", all))
        file.addType(fixedOutcome(MALFORMED, "detail", "An answer outside the contract.", all))
        file.addType(requestType(GATED, "A request to a gated operation: only it may carry the device token."))
        file.addType(requestType(OPEN, "A request to an open operation: it never carries a token."))
        return file.build()
    }

    private fun routeReply(d: WireDescription, op: WireOperation): TypeSpec {
        val reply = replyName(op)
        val type = TypeSpec.interfaceBuilder(reply).addModifiers(KModifier.SEALED).addSuperinterface(REPLY)
            .addKdoc("What `${op.id}` (`${op.method} ${op.path}`) answers.")
        op.outcomes().filter { it.name !in d.sharedOutcomes }.distinctBy { it.name }.forEach { outcome ->
            type.addType(outcomeType(outcome, ClassName(PACKAGE, outcome.group), listOf(reply)))
        }
        return type.build()
    }

    private fun outcomeType(outcome: WireOutcome, group: ClassName, replies: List<ClassName>): TypeSpec {
        val fields = fields(outcome)
        val builder = if (fields.isEmpty()) {
            TypeSpec.objectBuilder(outcome.name).addModifiers(KModifier.DATA)
        } else {
            TypeSpec.classBuilder(outcome.name).addModifiers(KModifier.DATA).primaryConstructor(
                FunSpec.constructorBuilder().apply {
                    fields.forEach { (n, t) -> addParameter(n, t) }
                }.build(),
            ).apply { fields.forEach { (n, t) -> addProperty(PropertySpec.builder(n, t).initializer(n).build()) } }
        }
        return builder.addSuperinterface(group).apply { replies.forEach { addSuperinterface(it) } }.build()
    }

    private fun fixedOutcome(name: ClassName, field: String, doc: String, replies: List<ClassName>): TypeSpec =
        TypeSpec.classBuilder(name).addModifiers(KModifier.DATA).addKdoc(doc)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter(field, String::class).build())
            .addProperty(PropertySpec.builder(field, String::class).initializer(field).build())
            .addSuperinterface(ClassName(PACKAGE, "NoAnswer"))
            .apply { replies.forEach { addSuperinterface(it) } }
            .build()

    private fun requestType(name: ClassName, doc: String): TypeSpec {
        val r = TypeVariableName("R", listOf(REPLY), KModifier.OUT)
        val decide = LambdaTypeName.get(parameters = arrayOf(WIRE_RESPONSE), returnType = TypeVariableName("R"))
        return TypeSpec.classBuilder(name).addKdoc(doc).addTypeVariable(r)
            .primaryConstructor(
                FunSpec.constructorBuilder().addParameter("wire", WIRE_REQUEST).addParameter("decide", decide).build(),
            )
            .addProperty(PropertySpec.builder("wire", WIRE_REQUEST).initializer("wire").build())
            .addProperty(PropertySpec.builder("decide", decide).initializer("decide").build())
            .build()
    }
}

package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClass

// The rules of the core's layers (`docs/patterns.md`): what an entry point may contain, where a coroutine may start,
// what may stand in for behaviour, and what the domain may hold. Each is scoped by its `includes` in
// `config/detekt/_base.yml`.

/**
 * A layer that does not decide (`docs/patterns.md`): no `if`, and a `when` only over a subject, with every case named —
 * no `else`, so a new case of the fact is a compile error rather than a silent default. [decider] names where a
 * decision belongs instead.
 */
abstract class BranchShape(config: Config, private val layer: String, private val decider: String) : Rule(config) {
    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        report(CodeSmell(issue, Entity.from(expression), "An `if` in $layer decides: move it into $decider."))
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        if (expression.subjectExpression == null) {
            report(CodeSmell(issue, Entity.from(expression), "A `when` in $layer selects over a subject."))
        }
        if (expression.elseExpression != null) {
            report(CodeSmell(issue, Entity.from(expression), "A `when` in $layer names every case: no `else`."))
        }
    }
}

/**
 * An entry point orders steps and decides nothing (`docs/patterns.md`, "Entry points"): [BranchShape]. A decision that
 * needs more is a feature's.
 */
class EntryPointShape(config: Config = Config.empty) : BranchShape(config, "an entry point", "a feature") {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "An entry point orders steps; a decision belongs in a feature.",
        Debt.TWENTY_MINS,
    )
}

/**
 * Data translates and never decides (`docs/patterns.md`, "The data layer"): [BranchShape], and every `when` is used as
 * an expression — its value is the answer, so no case can quietly do nothing. A `when` standing as a statement in a
 * block is reported; one that is a lambda's last statement is the lambda's value and passes.
 */
class DataShape(config: Config = Config.empty) : BranchShape(config, "data", "a domain verdict") {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Data translates; a decision belongs in a domain verdict.",
        Debt.TWENTY_MINS,
    )

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        val block = expression.parent as? KtBlockExpression ?: return
        val lambdaValue = block.parent is KtFunctionLiteral && block.statements.lastOrNull() == expression
        if (!lambdaValue) {
            report(CodeSmell(issue, Entity.from(expression), "A `when` in data is used as an expression: its value."))
        }
    }
}

/**
 * A step is one call (`docs/patterns.md`, "Steps"): `required { a() }`, `isolated("x") { b() }` — the name of the step
 * is what it does, so the list reads as the trigger's overview. A step that needs two calls is two steps, or a
 * sequence.
 */
class OneCallPerStep(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "A step holds one call.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text !in STEPS) return
        val body = expression.lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression ?: return
        if (body.statements.size != 1) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "`${expression.calleeExpression?.text}` holds ${body.statements.size} statements: one call per step.",
                ),
            )
        }
    }

    private companion object {
        val STEPS = setOf("required", "isolated", "child", "ensure", "detached")
    }
}

/**
 * Only the runtime starts coroutines (`docs/patterns.md`, "Runs"): a run is started by an entry point's base, detached
 * work by `detached`, outliving work by `outliving` — each labelled, held and contained by `scheduling`. A `launch`,
 * an `async` or a scope built anywhere else escapes all three.
 */
class CoroutinesInScheduling(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "Coroutines are started by the runtime only.",
        Debt.TWENTY_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        if (name in STARTERS) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "`$name` starts a coroutine outside the runtime: use a step (`detached`, `outliving`) instead.",
                ),
            )
        }
    }

    private companion object {
        val STARTERS = setOf(
            "launch", "async", "runBlocking", "CoroutineScope", "MainScope", "coroutineScope", "supervisorScope",
            "GlobalScope", "produce", "actor",
        )
    }
}

/**
 * No behaviour stands in for a type (`docs/patterns.md`, "The inbound kinds"): no function-typed constructor
 * parameter or property, no `fun interface`, no type alias to a function type. A function-typed field is a seam
 * nothing names — the shape of an adapter written inline and of an unwired callback alike; an interface names it. A
 * function's own parameter (a step's block, a builder's lambda) is a call's argument, not a seam, and passes.
 */
class NoFunctionTypes(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Behaviour crosses as an interface, never as a function-typed field.",
        Debt.TWENTY_MINS,
    )

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (parameter.ownerFunction?.let { it.containingClass()?.primaryConstructor == it } == true &&
            parameter.typeReference.isFunctionType()
        ) {
            report(
                CodeSmell(issue, Entity.from(parameter), "A function-typed constructor parameter: name an interface."),
            )
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.typeReference.isFunctionType()) {
            report(CodeSmell(issue, Entity.from(property), "A function-typed property: name an interface."))
        }
    }

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface() && klass.hasModifier(KtTokens.FUN_KEYWORD)) {
            report(CodeSmell(issue, Entity.from(klass), "A `fun interface` converts from a lambda: a plain interface."))
        }
    }

    override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        super.visitTypeAlias(typeAlias)
        if (typeAlias.getTypeReference().isFunctionType()) {
            report(CodeSmell(issue, Entity.from(typeAlias), "A type alias to a function type: name an interface."))
        }
    }

    private fun KtTypeReference?.isFunctionType(): Boolean {
        val element = this?.typeElement ?: return false
        return element is KtFunctionType || (element as? KtNullableType)?.innerType is KtFunctionType
    }
}

/**
 * The domain is values and verdicts (`docs/patterns.md`, "The domain"): nothing suspends, nothing changes, nothing is
 * passed as behaviour, nothing reads the world, and every verdict lives on its type. Its module depends on nothing, so
 * the compiler withholds ports, coroutines and formats; this holds what the standard library still allows.
 *
 * Syntax only — K/N has no type resolution here: a mutable type behind an inferred type it does not name, and
 * non-determinism read inside a call, are review's. A local `var` (a loop's counter) is not state, and `by lazy` is the
 * memo of a value; both pass, as does a `buildList` RETURNED from a function.
 */
class DomainPurity(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "The domain is values and verdicts: no suspension, no mutable state, no behaviour as a value, no world.",
        Debt.TWENTY_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
            report(
                CodeSmell(issue, Entity.from(function), "A suspending function: a verdict decides, it does not wait."),
            )
        }
        if (function.isTopLevel) {
            report(
                CodeSmell(issue, Entity.from(function), "A top-level function: a verdict or codec lives on its type."),
            )
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.isLocal) return
        if (property.isVar) report(CodeSmell(issue, Entity.from(property), "A `var` property is state: a value."))
        if (property.isTopLevel && property.receiverTypeReference != null) {
            report(CodeSmell(issue, Entity.from(property), "A top-level extension property: it lives on its type."))
        }
        val named = listOfNotNull(
            property.typeReference?.text,
            (property.initializer as? KtCallExpression)?.calleeExpression?.text,
        )
        if (named.any { text -> MUTABLE.any { Regex("""\b$it\b""").containsMatchIn(text) } }) {
            report(CodeSmell(issue, Entity.from(property), "A mutable value held as a property: hold a read-only one."))
        }
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val type = parameter.typeReference?.typeElement
        if (type is KtFunctionType || (type as? KtNullableType)?.innerType is KtFunctionType) {
            report(CodeSmell(issue, Entity.from(parameter), "A function-typed parameter: a verdict takes values."))
        }
        if (parameter.hasValOrVar() && parameter.isMutable) {
            report(CodeSmell(issue, Entity.from(parameter), "A `var` constructor property is state: a value."))
        }
    }

    override fun visitTypeReference(typeReference: KtTypeReference) {
        super.visitTypeReference(typeReference)
        if (typeReference.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
            report(CodeSmell(issue, Entity.from(typeReference), "A suspending function type: a verdict does not wait."))
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text
        if (name in CONSOLE) report(CodeSmell(issue, Entity.from(expression), "`$name` reads or writes the world."))
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val text = (expression.parent as? KtDotQualifiedExpression)?.takeIf { it.receiverExpression == expression }?.text
        if (text != null && WORLD.any { text.startsWith(it) }) {
            report(CodeSmell(issue, Entity.from(expression), "`$text` reads the world: take the value as an argument."))
        } else if (expression.text == "Random" && expression.parent !is KtDotQualifiedExpression) {
            report(
                CodeSmell(issue, Entity.from(expression), "`Random` reads the world: take the value as an argument."),
            )
        }
    }

    private companion object {
        val MUTABLE = listOf(
            "MutableList", "MutableSet", "MutableMap", "MutableCollection", "ArrayList", "HashMap", "HashSet",
            "LinkedHashMap", "LinkedHashSet", "ArrayDeque", "Array", "IntArray", "LongArray", "ByteArray",
            "StringBuilder", "mutableListOf", "mutableSetOf", "mutableMapOf", "arrayListOf", "hashMapOf", "hashSetOf",
            "arrayOf", "buildList", "buildSet", "buildMap",
        )
        val CONSOLE = setOf("println", "print", "readln", "readlnOrNull")
        val WORLD = listOf("Clock.System", "TimeSource.Monotonic", "Random.", "Uuid.random")
    }
}

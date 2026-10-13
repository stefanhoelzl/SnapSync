package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClass

// The rules of the core's layers (`docs/patterns.md`): what an entry point may contain, where a coroutine may start,
// and what may stand in for behaviour. Each is scoped by its `includes` in `config/detekt/_base.yml`.

/**
 * An entry point orders steps and decides nothing (`docs/patterns.md`, "Entry points"): no `if`, and a `when` only over
 * a subject, with every case named — no `else`, so a new case of the fact is a compile error rather than a silent
 * default. A decision that needs more is a feature's.
 */
class EntryPointShape(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "An entry point orders steps; a decision belongs in a feature.",
        Debt.TWENTY_MINS,
    )

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        report(CodeSmell(issue, Entity.from(expression), "An `if` in an entry point decides: move it into a feature."))
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        if (expression.subjectExpression == null) {
            report(CodeSmell(issue, Entity.from(expression), "A `when` in an entry point selects over a subject."))
        }
        if (expression.elseExpression != null) {
            report(CodeSmell(issue, Entity.from(expression), "A `when` in an entry point names every case: no `else`."))
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
        if (klass.isInterface() && klass.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.FUN_KEYWORD)) {
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

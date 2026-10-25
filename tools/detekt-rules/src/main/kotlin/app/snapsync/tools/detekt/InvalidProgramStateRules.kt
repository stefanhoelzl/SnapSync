package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

// The one exception the core raises (`docs/patterns.md`, "Invalid program state"): `InvalidProgramState`, thrown only
// where the state is guaranteed never to happen, its `because` naming the guarantee.

/**
 * In the core's layers the only throw is `throw InvalidProgramState("<literal>")`, or a rethrow of what a `catch` caught
 * or a function was handed. Every expected failure is a value; nothing else may throw for it either — no `require`,
 * `check`, `error`, `requireNotNull`, `checkNotNull` or `TODO`, no `!!`, and none of the standard library's calls that
 * throw on an empty or missing value (`first`, `last`, `single`, `reduce`, `getValue`, `getOrThrow`), each of which has
 * a twin that answers `null`. Read as syntax: a conversion that throws on bad text (`String.toInt()`) cannot be told
 * from one that cannot without types, and is review's.
 */
class OnlyInvalidProgramStateThrows(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "The core throws only an InvalidProgramState; every expected failure is a value.",
        Debt.TWENTY_MINS,
    )

    override fun visitThrowExpression(expression: KtThrowExpression) {
        super.visitThrowExpression(expression)
        val thrown = expression.thrownExpression ?: return
        if (isInvalidProgramState(thrown) || isRethrow(thrown)) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Only `throw InvalidProgramState(\"…\")` or a rethrow: an expected failure is a value.",
            ),
        )
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val receiver = (expression.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == expression }
        val asserting = receiver == null && name in ASSERTIONS
        val emptyThrows = receiver != null && name in THROWING_READS &&
            expression.valueArgumentList?.arguments.orEmpty().isEmpty()
        if (asserting || emptyThrows || (receiver != null && name in ALWAYS_THROWING)) {
            report(CodeSmell(issue, Entity.from(expression), "`$name` throws: answer the failure as a value."))
        }
    }

    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        super.visitPostfixExpression(expression)
        if (expression.operationToken == KtTokens.EXCLEXCL) {
            report(CodeSmell(issue, Entity.from(expression), "`!!` throws: answer the absence as a value."))
        }
    }

    private fun isInvalidProgramState(thrown: KtExpression): Boolean =
        (thrown as? KtCallExpression)?.calleeExpression?.text == INVALID_PROGRAM_STATE

    /** A name a `catch` caught, or a parameter of the function or lambda the throw is in. */
    private fun isRethrow(thrown: KtExpression): Boolean {
        val name = (thrown as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        return thrown.parents.any { scope ->
            when (scope) {
                is KtCatchClause -> scope.catchParameter?.name == name
                is KtFunctionLiteral -> scope.valueParameters.any { it.name == name }
                is KtDeclarationWithBody -> scope.valueParameters.any { it.name == name }
                else -> false
            }
        }
    }

    private companion object {
        val ASSERTIONS = setOf("require", "check", "error", "requireNotNull", "checkNotNull", "TODO")

        /** Reads that throw on an empty receiver unless given an argument: `first()` and `first { }` both throw. */
        val THROWING_READS = setOf("first", "last", "single")

        val ALWAYS_THROWING = setOf("reduce", "getValue", "getOrThrow")
    }
}

/**
 * An `InvalidProgramState`'s `because` is a string literal, never interpolated: it names the guarantee, carries no
 * value — no key, no id — into the crash report, and groups as one crash wherever it is thrown.
 */
class InvalidProgramStateBecause(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "An InvalidProgramState's because is a literal naming the guarantee.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != INVALID_PROGRAM_STATE) return
        val because = expression.valueArguments.singleOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
        if (because == null || because.hasInterpolation()) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "An InvalidProgramState names its guarantee in one literal, with no `$` in it.",
                ),
            )
        }
    }
}

private const val INVALID_PROGRAM_STATE = "InvalidProgramState"

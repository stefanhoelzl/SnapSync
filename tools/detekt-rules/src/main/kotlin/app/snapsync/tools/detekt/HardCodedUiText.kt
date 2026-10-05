package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Words in a UI module's code (`docs/architecture.md`, "Localization"): every string a person reads comes from
 * `strings.xml`, so a translation is a file and never a code change. A string literal with a letter in it is
 * therefore a finding — in the scope this rule is configured for, the screens and the design system.
 *
 * What is NOT words, and passes: a literal with no letter (`" · "`, `"--"`), an annotation's argument, a message
 * for a developer (`error`, `require`, `check` and kin), a test tag, an animation's debug label, and a date
 * skeleton handed to `format`. Anything else that is not read by a person — a diagnostic label, say — says so
 * with `@Suppress("HardCodedUiText")` where it is written.
 */
class HardCodedUiText(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "A string a person reads belongs in strings.xml, where it can be translated.",
        Debt.FIVE_MINS,
    )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        if (!expression.hasWords() || expression.getParentOfType<KtAnnotationEntry>(true) != null) return
        val call = expression.enclosingCall()?.calleeName()
        if (call != null && (call in DEVELOPER_CALLS || call.startsWith("animate") || call.endsWith("Transition"))) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Hard-coded text ${expression.text}: put it in strings.xml and read it with stringResource.",
            ),
        )
    }

    private companion object {
        val DEVELOPER_CALLS = setOf(
            "error", "require", "requireNotNull", "check", "checkNotNull", "TODO", "assert",
            "testTag", "format",
        )
    }
}

/**
 * Words the OS shows for the Android adapter — a notification's title, a channel's name (`docs/architecture.md`,
 * "Localization"). They come from the adapter's generated `res/values/strings.xml` through `getString`; a literal
 * passed to one of these calls is a finding. The adapter's other strings (ids, log lines, column names) are not
 * words anyone reads, which is why this rule names calls instead of flagging every literal.
 */
class HardCodedSystemText(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Text the OS shows belongs in strings.xml, where it can be translated.",
        Debt.FIVE_MINS,
    )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        if (!expression.hasWords()) return
        val argument = expression.parent as? KtValueArgument ?: return
        val call = argument.getParentOfType<KtCallExpression>(true) ?: return
        val shown = SHOWN_ARGUMENTS[call.calleeName()] ?: return
        if (call.valueArguments.indexOf(argument) in shown) {
            report(CodeSmell(issue, Entity.from(expression), "Hard-coded system text ${expression.text}."))
        }
    }

    private companion object {
        /** The calls whose arguments the OS shows, by position. */
        val SHOWN_ARGUMENTS = mapOf(
            "NotificationChannel" to setOf(1),
            "setContentTitle" to setOf(0),
            "setContentText" to setOf(0),
            "setSubText" to setOf(0),
            "setTicker" to setOf(0),
            "makeText" to setOf(1),
        )
    }
}

/** Whether the literal parts of this string hold a letter — the line between words and punctuation. */
private fun KtStringTemplateExpression.hasWords(): Boolean = entries.any { entry ->
    (entry is KtLiteralStringTemplateEntry || entry is KtEscapeStringTemplateEntry) && entry.text.any { it.isLetter() }
}

/** The nearest call this string is written in — an argument, or the lambda one takes (`require(ok) { "…" }`). */
private fun KtStringTemplateExpression.enclosingCall(): KtCallExpression? = getParentOfType<KtCallExpression>(true)

private fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

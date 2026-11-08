package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * **A function lives on the type it belongs to** (`docs/patterns.md`, "No top-level functions"): a verdict on its
 * value's type, a translation on the class that translates, a helper as a private member of the one class that uses it.
 * A top-level function is code with no owner — found by grep, never by its type — which is how the old zones grew.
 *
 * The one exception is [allowed]: each composition module's composing function (`CompositionIsWiringTest` lists
 * them), the straight-line entry a root calls — a process has exactly one, and it is no type's member.
 */
class NoTopLevelFunctions(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "A function lives on the type it belongs to: no top-level function.",
        Debt.TWENTY_MINS,
    )

    private val allowed: List<String> by config(emptyList())

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.parent !is KtFile || function.name in allowed) return
        report(
            CodeSmell(issue, Entity.from(function), "`${function.name}` is a top-level function: give it its owner."),
        )
    }
}

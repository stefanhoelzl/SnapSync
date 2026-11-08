package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * **A constructor never touches a port** (`docs/patterns.md`, "Composition is wiring"): what a class does as it is
 * built — a property's initializer, an `init` block — never calls or reads through what it was handed. A store opens on
 * first use, an identity resolves on its first read; so a graph built eagerly opens nothing, and a locked background
 * launch is never forced into an open by its composition.
 *
 * A constructor parameter whose type this file imports from `app.snapsync.ports` — a port, read or called — reached
 * through `.` or `?.` while the object is built is reported. Syntax only: a port reached through another object is the
 * review's. Handing a parameter to another constructor, or using it inside a lambda or an object expression (which run
 * later), passes; so does a delegated property (`by lazy`), whose initializer runs on first use.
 */
class ConstructorsTouchNoPort(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "A constructor never touches what it was handed: open, read and resolve on first use.",
        Debt.TWENTY_MINS,
    )

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val owner = property.containingClass() ?: return
        if (property.isLocal || property.parents.any { it is KtLambdaExpression }) return
        property.initializer?.let { report(owner, it) }
    }

    override fun visitAnonymousInitializer(initializer: KtAnonymousInitializer) {
        super.visitAnonymousInitializer(initializer)
        val owner = initializer.containingClass() ?: return
        initializer.body?.let { report(owner, it) }
    }

    private fun report(owner: KtClass, built: KtExpression) {
        val ports = portTypes(owner.containingKtFile)
        val parameters = owner.primaryConstructorParameters
            .filter { it.typeReference?.text?.removeSuffix("?") in ports }
            .mapNotNull { it.name }
            .toSet()
        (listOf(built).filterIsInstance<KtQualifiedExpression>() + built.collectDescendantsOfType<KtQualifiedExpression>())
            .distinct()
            .filterNot { it.deferred(built) }
            .filter { (it.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() in parameters }
            .forEach {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(it),
                        "`${it.text}` reaches a constructor parameter while the object is built: do it on first use.",
                    ),
                )
            }
    }

    /** The simple names this file imports from the ports — what its constructor parameters may be typed as. */
    private fun portTypes(file: KtFile): Set<String> = file.importDirectives
        .mapNotNull { it.importedFqName?.asString() }
        .filter { it.startsWith(PORTS) }
        .map { it.substringAfterLast('.') }
        .toSet()

    /** Whether [this] runs later than the build: inside a lambda or an object expression within [built]. */
    private fun KtElement.deferred(built: KtExpression): Boolean = parents
        .takeWhile { it != built.parent }
        .any { it is KtLambdaExpression || it is KtObjectLiteralExpression }
}

private const val PORTS = "app.snapsync.ports."

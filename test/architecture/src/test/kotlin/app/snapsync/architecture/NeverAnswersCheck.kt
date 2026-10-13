package app.snapsync.architecture

/**
 * **Whether an adapter's `@NeverAnswers` claim holds in its compiled code** (`docs/testing.md`, "Every adapter is
 * covered"). One target's [CodeBase] at a time; a class linked by several targets must hold its claim on each.
 *
 * The check may only err towards REFUSING a true claim, never towards accepting a false one:
 *
 * - **A variant or an enum entry** ([Claim.Answer]): starting at the members the claim names — the ones this class
 *   itself declares (an inherited or delegated member is its declarer's) — every function our code may run is walked:
 *   calls into our code are followed, a dispatch to every implementation this target links, a lambda or reference to
 *   its body, a field to its initializer and every write to it. The claim is refused where that code builds the
 *   variant, reads the entry or the enum's whole set (`entries`, `values()`, `valueOf`), or where a value the walk
 *   cannot see enters with a type that could BE the answer: the result of a call into the SDK, the stdlib or a
 *   function value, a cast, a parameter of the member or of a lambda, or a field nobody here writes.
 * - **A constant** ([Claim.Constant]): no walk at all. The member's own returns must be the opposite literal
 *   (`true`/`false`), all `null` (for a claim it never answers `returns`), or its declared return type non-null (for a
 *   claim it never answers `null` — the compiler's own proof). A property counts through the field it returns, when one
 *   literal initializes it and nothing else writes it.
 * - **A handler** ([Claim.Handler]): every function of the class is walked as above, and the claim is refused where
 *   any of it reads the handler from its bundle — the only way Kotlin code reaches a handler to call it.
 * - **A handler's argument** ([Claim.HandlerArgument]): the functions of the class that read the handler are the
 *   roots, their parameters open, and the walk is the answer walk above — so whatever reaches the call, the answer is
 *   not built, read or taken in there.
 */
internal object NeverAnswersCheck {

    sealed interface Claim {
        /** The members the claim names (their port names); empty for every member of the class's ports. */
        val members: Set<String>

        /** [fqn] is a sealed leaf's class, or an enum's class with [entry] set. */
        data class Answer(override val members: Set<String>, val fqn: String, val entry: String? = null) : Claim

        /** [value] is `true`, `false`, `null` or `returns`: the answer [members]' single member never gives. */
        data class Constant(override val members: Set<String>, val value: String) : Claim

        /** The handler [field] of the bundle class [bundle] (FQN). */
        data class Handler(val bundle: String, val field: String) : Claim {
            override val members = emptySet<String>()
        }

        /** The handler [handler] is never called with [answer] (a variant of one of its parameters). */
        data class HandlerArgument(val handler: Handler, val answer: Answer) : Claim {
            override val members = emptySet<String>()
        }
    }

    /** `null` when [claim] holds for [adapter] in [base]; otherwise where the answer may be produced. */
    fun refusal(base: CodeBase, adapter: String, claim: Claim): String? {
        val cls = base.classes[adapter] ?: return "$adapter is not linked on ${base.target.label}"
        return when (claim) {
            is Claim.Constant -> claim.members.firstNotNullOfOrNull { constantRefusal(base, cls, it, claim.value) }
            is Claim.Answer -> {
                val roots = memberFuns(cls, claim.members)
                if (roots.isEmpty()) return "$adapter declares none of ${claim.members}"
                Walk(base, roots).find { facts, fn -> answerSign(base, facts, fn, claim) }
            }
            is Claim.Handler -> {
                val roots = cls.funs.filterNot { it.inherited || it.abstract }
                Walk(base, roots, initializersOf = cls).find { facts, _ ->
                    facts.calls.firstOrNull {
                        isHandlerRead(
                            it,
                            claim,
                        )
                    }?.let { "reads ${claim.field} from its handlers" }
                }
            }
            is Claim.HandlerArgument -> {
                // Every function that reaches the handler — a lambda's body included — is where its arguments are made.
                val own = (cls.funs + base.funs.values.filter { it.owner.startsWith("${cls.fqn}.") || it.owner == cls.fqn })
                    .distinctBy { it.key }
                    .filterNot { it.inherited || it.abstract }
                val callers = entriesReaching(
                    base,
                    own,
                    own.filter { f -> f.facts.calls.any { isHandlerRead(it, claim.handler) } },
                    claim.answer.fqn,
                )
                Walk(base, callers).find { facts, fn -> answerSign(base, facts, fn, claim.answer) }
            }
        }
    }

    /**
     * Where [targets]' arguments come from: each one itself, unless it is private and reads a parameter that could be
     * [answer] — then the functions of [own] that call it, followed up while those are such too. A private function's
     * parameters are its callers' values, so starting there lets the walk see what they are; a function nothing here
     * calls stays open, as it must.
     */
    private fun entriesReaching(
        base: CodeBase,
        own: List<CodeFun>,
        targets: List<CodeFun>,
        answer: String,
    ): List<CodeFun> {
        val out = linkedMapOf<String, CodeFun>()
        val seen = mutableSetOf<String>()
        val todo = ArrayDeque(targets)
        while (todo.isNotEmpty()) {
            val f = todo.removeFirst()
            if (!seen.add(f.key)) continue
            val callers = own.filter { c -> c.facts.calls.any { call -> base.targets(call).any { it.key == f.key } } }
            val takesTheAnswer = f.facts.paramReads.any { (type, _) -> carries(base, type, answer) }
            if (!f.private || !takesTheAnswer || callers.isEmpty()) out[f.key] = f else todo += callers
        }
        return out.values.toList()
    }

    /** The functions of [cls] that implement [members], declared by [cls] itself — not inherited, not delegated. */
    fun memberFuns(cls: CodeClass, members: Set<String>): List<CodeFun> =
        cls.funs.filterNot {
            it.inherited || it.abstract || it.delegated
        }.filter { f -> members.any { m -> f.name in jvmOrIrNames(m) } }

    /** The names a port member [m] compiles to: itself, its IR getter, its JVM getter. */
    fun jvmOrIrNames(m: String): Set<String> {
        val cap = m.replaceFirstChar { it.uppercase() }
        return setOf(m, "<get-$m>", "get$cap", "$m\$suspendImpl")
    }

    private fun constantRefusal(base: CodeBase, cls: CodeClass, member: String, value: String): String? {
        val funs = memberFuns(cls, setOf(member))
        if (funs.isEmpty()) return "${cls.fqn} declares no $member"
        val allowed = OPPOSITE[value]
        if (value != "null" && allowed == null) return "a constant claim answers true, false, null or returns — not $value"
        return funs.firstNotNullOfOrNull { f ->
            if (value == "null") nullRefusal(f) else literalRefusal(base, cls, f, allowed.orEmpty())
        }
    }

    /** Why [f] may answer `null`: it answers nothing to read, or declares a nullable answer. */
    private fun nullRefusal(f: CodeFun): String? {
        val type = f.returnType ?: return "${f.owner}.${f.name} returns nothing"
        return if (type.nullable) "${f.owner}.${f.name} declares a nullable answer ($type)" else null
    }

    /** Why [f] may answer other than an [allowed] literal: a return that is not one, computed returns included. */
    private fun literalRefusal(base: CodeBase, cls: CodeClass, f: CodeFun, allowed: Set<String>): String? {
        val returned = f.facts.returns.map { literalOf(base, cls, it) }
        if (returned.isEmpty()) return "${f.owner}.${f.name}: no return found to read"
        // A computed return reads as `null` here, and is never allowed.
        val other = returned.indexOfFirst { it == null || it !in allowed }
        return if (other >= 0) "${f.owner}.${f.name} may answer ${returned[other] ?: "a computed value"}" else null
    }

    /** What [r] is as a literal: itself, or the one literal a never-rewritten field holds; `null` when computed. */
    private fun literalOf(base: CodeBase, cls: CodeClass, r: Returned): String? = when (r) {
        is Returned.Literal -> r.text
        is Returned.Other -> null
        is Returned.Field -> {
            val init = base.classes[r.field.owner]?.fieldInits?.get(r.field.name)?.returns?.singleOrNull()
            val writers = base.writersOf(r.field)
            val rewritten = writers.any { f -> f.name != "<init>" && f.name != "<clinit>" }
            when {
                rewritten -> null
                init != null -> (init as? Returned.Literal)?.text
                // Never written at all: the JVM's default, which the compiler leaves a `= false`/`= null` to.
                writers.isEmpty() -> when (base.classes[r.field.owner]?.fieldTypes?.get(r.field.name)?.fqn) {
                    "boolean" -> "false"
                    null, "int", "long", "short", "byte", "char", "float", "double" -> null
                    else -> "null"
                }
                else -> null
            }
        }
    }

    /** Where [facts] may produce [claim]'s answer: built or read here, or taken in from code the walk cannot see. */
    private fun answerSign(base: CodeBase, facts: Facts, fn: Step, claim: Claim.Answer): String? =
        madeHere(facts, claim) ?: takenIn(base, facts, fn, claim)

    /** The answer built (a variant) or read (an entry, or the enum's whole set) by this code itself. */
    private fun madeHere(facts: Facts, claim: Claim.Answer): String? {
        if (claim.entry == null) return if (claim.fqn in facts.builds) "builds ${short(claim.fqn)}" else null
        if ("${claim.fqn}.${claim.entry}" in facts.enumEntries) return "reads ${short(claim.fqn)}.${claim.entry}"
        return facts.calls.firstOrNull { it.owner == claim.fqn && it.name in BULK }
            ?.let { "reads every ${short(claim.fqn)} (${it.name})" }
    }

    /** A value of a type that could be the answer, entering from code the walk cannot see. */
    private fun takenIn(base: CodeBase, facts: Facts, fn: Step, claim: Claim.Answer): String? {
        val carries = { t: CodeType -> carries(base, t, claim.fqn) }
        val opaque = { s: Source -> opaque(base, facts, fn, s, claim.fqn) }
        return facts.calls.firstOrNull { c -> c.type != null && carries(c.type) && base.targets(c).isEmpty() }
            ?.let { "takes a ${it.type} from ${it.owner ?: "?"}.${it.name}, whose code is not ours" }
            ?: facts.inflows.firstOrNull { carries(it.type) }?.let { "takes a ${it.type} from ${it.origin}" }
            ?: facts.casts.firstOrNull { c -> carries(c.type) && c.sources.any(opaque) }
                ?.let { c -> "casts to ${c.type} a value from ${describe(c.sources.first(opaque))}" }
            ?: facts.paramReads.firstOrNull { (t, declaredIn) -> carries(t) && fn.opensParameters(declaredIn) }
                ?.let { "takes a ${it.first} as a parameter of ${it.second}" }
    }

    /**
     * Whether a cast's value from [s] could be the answer without the walk having seen it built: a call into code that is
     * not ours, a parameter from outside, a field nobody here types as the answer, what a suspension resumed with when
     * one of its calls is not ours, or a value of a static type that cannot already be the answer (IR).
     */
    private fun opaque(base: CodeBase, facts: Facts, fn: Step, s: Source, answer: String): Boolean = when (s) {
        is Source.Typed -> !carries(base, s.type, answer)
        is Source.Call -> base.targets(s.ref).isEmpty()
        is Source.Param -> fn.opensParameters(s.declaredIn)
        is Source.Field -> base.classes[s.field.owner]?.fieldTypes?.get(s.field.name)?.let {
            !carries(base, it, answer)
        } ?: true
        is Source.SuspendResult -> facts.calls.any { it.key?.endsWith(SUSPEND_TAIL) == true && base.targets(it).isEmpty() }
        is Source.Ours -> false
        is Source.Unknown -> true
    }

    private fun describe(s: Source): String = when (s) {
        is Source.Typed -> "a ${s.type}"
        is Source.Call -> "${s.ref.owner}.${s.ref.name}, whose code is not ours"
        is Source.Param -> "a parameter of ${s.declaredIn}"
        is Source.Field -> "the field ${s.field.owner}.${s.field.name}"
        is Source.SuspendResult -> "a suspend call whose code is not ours"
        is Source.Ours -> "here"
        is Source.Unknown -> s.what
    }

    private const val SUSPEND_TAIL = "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"

    private fun isHandlerRead(c: CallRef, claim: Claim.Handler): Boolean {
        val cap = claim.field.replaceFirstChar { it.uppercase() }
        val ir = c.key?.substringBefore('|')?.let { IrReader.fqnOfSignature(it) }
        return ir == "${claim.bundle}.${claim.field}.<get-${claim.field}>" ||
            (c.owner == claim.bundle && (c.name == "get$cap" || c.name == "<get-${claim.field}>"))
    }

    /**
     * Whether a value of [type] could be [answer]: the answer's own class, one it extends, or a type parameter. `Any` is
     * not: a value of it becomes the answer only through a cast, which the walk sees.
     */
    fun carries(base: CodeBase, type: CodeType, answer: String): Boolean {
        if (type.fqn == TYPE_PARAMETER) return true
        if (type.fqn in NEUTRAL) return false
        return type.fqn == answer || type.fqn in base.ancestors(answer)
    }

    private fun short(fqn: String) = fqn.split(
        '.',
    ).dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")

    /** The function the walk is in: whether its parameters come from code the walk cannot see. */
    class Step(val fn: CodeFun?, val open: Boolean, val parent: Step?) {
        /**
         * Whether a parameter declared in [declaredIn] (IR: `Owner.fun`; JVM: the method's key) comes from code the walk
         * cannot see: this step's own when it is entered from outside, a constructor's, a lambda's.
         */
        fun opensParameters(declaredIn: String): Boolean =
            open || declaredIn.endsWith(".<init>") || ".<init> " in declaredIn || declaredIn.endsWith("<anonymous>") ||
                "\$lambda" in declaredIn

        fun trail(): String = generateSequence(this) { it.parent }.mapNotNull { it.fn }.toList().reversed()
            .joinToString(" → ") { "${it.owner.substringAfterLast('.')}.${it.name}" }
    }

    /**
     * The walk: [roots] — whose parameters come from outside — and every function our code may run from them. [find]
     * answers the first sign its check names, with the call trail that reached it.
     */
    private class Walk(val base: CodeBase, val roots: List<CodeFun>, val initializersOf: CodeClass? = null) {
        private val seen = mutableSetOf<String>()
        private val todo = ArrayDeque<Pair<Facts, Step>>()

        fun find(check: (Facts, Step) -> String?): String? {
            roots.forEach { todo += it.facts to Step(it, open = true, parent = null) }
            initializersOf?.fieldInits?.values?.forEach { todo += it to Step(null, open = true, parent = null) }
            while (todo.isNotEmpty()) {
                val (facts, step) = todo.removeFirst()
                check(facts, step)?.let { return "${step.trail().ifEmpty { "an initializer" }}: $it" }
                follow(facts, step)
            }
            return null
        }

        /** Queues what [facts] may run: the calls' targets, what it hands on, its local classes, the fields it reads. */
        private fun follow(facts: Facts, step: Step) {
            facts.calls.forEach { c -> base.targets(c).forEach { visit(it, open = false, step) } }
            facts.handsOn.forEach { k -> base.funs[k]?.let { visit(it, open = true, step) } }
            facts.builds.mapNotNull { base.classes[it] }.filter { it.local }.forEach { c ->
                c.funs.forEach {
                    visit(
                        it,
                        open = true,
                        step,
                    )
                }
            }
            facts.fieldReads.forEach { (ref, type) -> field(ref, type, step) }
        }

        private fun visit(f: CodeFun, open: Boolean, step: Step) {
            if (seen.add(f.key)) todo += f.facts to Step(f, open, step)
        }

        /** A field read: its initializer, and every write to it — a write may be entered from anywhere. */
        private fun field(ref: FieldRef, type: CodeType, step: Step) {
            // An `object`'s instance or an enum entry holds itself, and nothing else ever.
            val constant = ref.name == "INSTANCE" || type.fqn == ref.owner
            if (constant || !seen.add("field:${ref.owner}.${ref.name}")) return
            base.classes[ref.owner]?.fieldInits?.get(ref.name)?.let { todo += it to Step(null, true, step) }
            base.writersOf(ref).forEach { visit(it, open = true, step) }
        }
    }

    private val BULK = setOf("values", "valueOf", "getEntries", "<get-entries>")

    /** What a constant claim leaves a member to answer: the literal opposite of the one it never gives. */
    private val OPPOSITE = mapOf("true" to setOf("false"), "false" to setOf("true"), "returns" to setOf("null"))
    private val NEUTRAL = setOf("kotlin.Any", "java.lang.Object", "kotlin.Unit", "kotlin.Nothing")
    const val TYPE_PARAMETER = "<type parameter>"
}

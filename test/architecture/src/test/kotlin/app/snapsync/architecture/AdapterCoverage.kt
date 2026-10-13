package app.snapsync.architecture

import app.snapsync.architecture.NeverAnswersCheck.Claim
import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * **Every adapter is covered** (`docs/testing.md`): the per-adapter reading of the port grid that [AdapterCoverageTest]
 * holds the build to.
 *
 * - **The adapters** are derived from the compiled production code of every target ([CodeBase]): each class that
 *   implements a grid port. A class linked by several targets is one adapter.
 * - **What an adapter owes** are the cells of its ports whose answering code it DECLARES: a member inherited from a
 *   superclass or delegated with `by` is owed by — and credited to — the class whose code answers it. A handler cell and
 *   a handle's cells belong to whoever declares the port's `listen`.
 * - **What covers an owed cell** is a clause that declares it and runs against a real implementation
 *   ([ContractCoverage.realBindings]) through a binding that names, as its `adapter`, a class whose code answers it — or
 *   a verified `@NeverAnswers` claim of the owing class ([NeverAnswersCheck]).
 * - **A stand-in answers what its real counterpart answers**: a simulator-only substitute every cell its iPhone
 *   counterpart is proven to answer, and the mocks every cell some real adapter of their port is proven to answer — each
 *   through its own bindings, of any kind, which count for nothing as proof of the real system.
 *
 * The backend's cells (`Backend.*`) have one real implementation, the `api/` itself, so they stay on
 * [ContractCoverage.coveredCells]' any-real-binding reading.
 */
internal object AdapterCoverage {

    val gridPorts: Map<String, KClass<*>> by lazy { PortGrid.ports.associateBy { "app.snapsync.ports.${it.simpleName}" } }

    val cellsByPort: Map<String, List<PortGrid.Cell>> by lazy {
        PortGrid.cells.filter { it.port != PortGrid.BACKEND }.groupBy { "app.snapsync.ports.${it.port}" }
    }

    /** One adapter: its FQN, the targets that link it, and the grid ports it implements. */
    class Adapter(
        val fqn: String,
        val targets: Set<CodeTarget>,
        val ports: Set<String>,
        val concrete: Boolean,
        val sourceSets: Set<String>,
    )

    val adapters: Map<String, Adapter> by lazy {
        val found = mutableMapOf<String, MutableList<Pair<CodeTarget, CodeClass>>>()
        CodeBase.all.forEach { (target, base) ->
            // A rig build compiles `src/rig` into the main klib: the rig's controls are no production code.
            base.classes.values.filter { !it.isInterface && !it.local && it.sourceSet != "rig" }.forEach { c ->
                if (base.ancestors(
                        c.fqn,
                    ).any { it in gridPorts }
                ) {
                    found.getOrPut(c.fqn) { mutableListOf() } += target to c
                }
            }
        }
        found.mapValues { (fqn, seen) ->
            val ports = seen.flatMap { (t, _) ->
                CodeBase.all.getValue(
                    t,
                ).ancestors(fqn).filter { it in gridPorts }
            }.toSet()
            Adapter(
                fqn,
                seen.map { it.first }.toSet(),
                ports,
                seen.none { it.second.isAbstract },
                seen.map { it.second.sourceSet }.toSet(),
            )
        }
    }

    /** The target a binding's host runs: the code its adapter is read from. */
    fun targetOf(host: String?): CodeTarget = when (host) {
        "Host.ANDROID_EMU" -> CodeTarget.ANDROID
        "Host.IOS_SIM_KEXE", "Host.IOS_SIM_APP" -> CodeTarget.IOS_SIMULATOR_ARM64
        "Host.IOS_DEVICE_APP", "Host.IOS_DEVICE_PHOTOKIT_EXT" -> CodeTarget.IOS_ARM64
        else -> CodeTarget.JVM
    }

    // ---- ownership -------------------------------------------------------------------------------------------------

    /**
     * The class whose code answers [member] for an instance of [fqn] on [base]: [fqn] itself when it declares it, the
     * superclass that does when it inherits it, the delegate's class when it delegates it (`by`) to a concrete class of
     * ours, else itself. `null` where no class of ours has a body for it (the port's default).
     */
    fun declarer(base: CodeBase, fqn: String, member: String): String? {
        var c = base.classes[fqn]
        val names = NeverAnswersCheck.jvmOrIrNames(member)
        while (c != null) {
            val f = c.funs.firstOrNull { !it.inherited && !it.abstract && it.name in names }
            if (f != null) {
                if (!f.delegated) return c.fqn
                val target = (f.facts.fieldReads.map { it.second.fqn } + c.delegates.map { it.fqn })
                    .firstOrNull { base.classes[it]?.let { d -> !d.isInterface } == true }
                return target?.let { declarer(base, it, member) } ?: c.fqn
            }
            c = c.supertypes.mapNotNull { base.classes[it] }.firstOrNull { !it.isInterface }
        }
        return null
    }

    /** The member a cell is owed through: its own member, or `listen` for a handler's or a handle's cell. */
    fun owningMember(cell: PortGrid.Cell): String {
        val afterPort = cell.text.substringAfter('.')
        val handlerOrHandle = afterPort.startsWith("handlers.") || afterPort.first().isUpperCase()
        return if (handlerOrHandle) "listen" else cell.member
    }

    /** The class owing [cell] for an instance of [adapter] on [base]; a handle of a non-event port is the adapter's own. */
    fun owner(base: CodeBase, adapter: String, cell: PortGrid.Cell): String? {
        val member = owningMember(cell)
        return declarer(base, adapter, member) ?: if (member == "listen") adapter else null
    }

    /** Every cell [adapter] owes, on every target that links it. */
    fun owed(adapter: Adapter): Set<PortGrid.Cell> = adapter.targets.flatMap { t ->
        val base = CodeBase.all.getValue(t)
        adapter.ports.flatMap { p -> cellsByPort[p].orEmpty() }.filter { owner(base, adapter.fqn, it) == adapter.fqn }
    }.toSet()

    // ---- credit ----------------------------------------------------------------------------------------------------

    /** A cell credited to an owner by a clause a binding ran — real, or (for the stand-in rule) of any kind. */
    class Credit(val owner: String, val cell: String, val binding: ContractCoverage.BindingDecl, val clause: String)

    private val cellByText: Map<String, PortGrid.Cell> by lazy { PortGrid.cells.associateBy { it.text } }

    private fun credits(real: Boolean): List<Credit> = ContractCatalog.contracts.flatMap { contract ->
        contract.clauses.flatMap { clause ->
            val bindings = if (real) {
                ContractCoverage.realBindings(
                    contract,
                    clause,
                )
            } else {
                ContractCoverage.reachingBindings(contract, clause)
            }
            bindings.flatMap { b ->
                val adapter = b.adapter ?: return@flatMap emptyList()
                val base = CodeBase.all.getValue(targetOf(b.host))
                clause.covers.mapNotNull { text ->
                    val cell = cellByText[text]?.takeIf { it.port != PortGrid.BACKEND } ?: return@mapNotNull null
                    Credit(owner(base, adapter, cell) ?: adapter, text, b, "${contract.name}/${clause.id}")
                }
            }
        }
    }

    val realCredits: List<Credit> by lazy { credits(real = true) }
    val anyCredits: List<Credit> by lazy { credits(real = false) }

    // ---- claims ----------------------------------------------------------------------------------------------------

    /** A claim as written on [adapter], the cells it names, and what the check made of it on each target. */
    class ClaimResult(val adapter: String, val written: String, val cells: Set<String>, val problem: String?)

    val claims: List<ClaimResult> by lazy {
        adapters.values.flatMap { a ->
            val written = a.targets.flatMap { t -> CodeBase.all.getValue(t).classes.getValue(a.fqn).claims }.distinct()
            written.map { (member, answer, handler) -> judge(a, member, answer, handler) }
        }
    }

    private fun judge(a: Adapter, member: String, answer: String, handler: String): ClaimResult {
        val text = listOfNotNull(
            member.takeIf { it.isNotEmpty() }?.let { "member = \"$it\"" },
            answer.takeIf { it.isNotEmpty() }?.let { "answer = \"$it\"" },
            handler.takeIf { it.isNotEmpty() }?.let { "handler = \"$it\"" },
        ).joinToString(", ", "Never(", ")")
        val resolved = ClaimResolution.resolve(a, member, answer, handler)
        if (resolved.second.isEmpty()) {
            return ClaimResult(
                a.fqn,
                text,
                emptySet(),
                resolved.third ?: "names no cell of ${a.ports.map { it.substringAfterLast('.') }}",
            )
        }
        val claim = resolved.first ?: return ClaimResult(a.fqn, text, resolved.second, resolved.third)
        val refusals = a.targets.mapNotNull { t ->
            NeverAnswersCheck.refusal(CodeBase.all.getValue(t), a.fqn, claim)?.let { "${t.label}: $it" }
        }
        return ClaimResult(a.fqn, text, resolved.second, refusals.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** Every owed cell of [adapter] that neither a real binding of its code nor one of its accepted claims covers. */
    fun uncovered(adapter: Adapter): Set<String> {
        val credited = realCredits.filter { it.owner == adapter.fqn }.map { it.cell }.toSet()
        val claimed = claims.filter { it.adapter == adapter.fqn && it.problem == null }.flatMap { it.cells }.toSet()
        return owed(adapter).map { it.text }.toSet() - credited - claimed
    }

    /**
     * The simulator-only substitutes and the iPhone adapters they stand in for, from the `expect` functions both iOS
     * targets compile: the port implementations a simulator `actual` builds that the iPhone one does not, paired with
     * those the iPhone one builds that share a port with them (else every one the iPhone one builds).
     */
    val substitutes: Map<String, Set<String>> by lazy {
        val sim = CodeBase.all.getValue(CodeTarget.IOS_SIMULATOR_ARM64)
        val arm = CodeBase.all.getValue(CodeTarget.IOS_ARM64)
        val out = mutableMapOf<String, MutableSet<String>>()
        sim.classes.values.filter { it.sourceSet == "iosSimulatorArm64Main" }.flatMap { it.funs }.forEach { f ->
            val twin = arm.funs[f.key]?.takeIf { arm.classes[it.owner]?.sourceSet == "iosArm64Main" } ?: return@forEach
            val simBuilds = built(sim, f)
            val armBuilds = built(arm, twin)
            val standIns = simBuilds - armBuilds
            val reals = (armBuilds - simBuilds).ifEmpty { armBuilds }
            standIns.forEach { s ->
                val ports = adapters[s]?.ports.orEmpty()
                val counterparts = reals.filter { r -> adapters[r]?.ports.orEmpty().any { it in ports } }
                if (counterparts.isNotEmpty()) out.getOrPut(s) { mutableSetOf() } += counterparts
            }
        }
        out
    }

    private fun built(base: CodeBase, f: CodeFun): Set<String> = f.facts.builds.filter { it in adapters }.toSet()

    /** The cells [counterpart] is proven to answer that [standIn]'s own bindings — of any kind — never ran. */
    fun standInGap(standIn: String, counterparts: Set<String>): Set<String> {
        val proven = realCredits.filter { it.owner in counterparts }.map { it.cell }.toSet()
        val ports = adapters[standIn]?.ports.orEmpty().map { it.substringAfterLast('.') }.toSet()
        val answered = anyCredits.filter { it.owner == standIn }.map { it.cell }.toSet()
        return proven.filter { cellByText[it]?.port in ports }.toSet() - answered
    }

    /**
     * A test double's binding: one bound in the mocks' module whose adapter is no production class (a mock, or a double
     * a test builds over one), or one bound in a `:test:*` module to a mock. It proves nothing of the real system. A
     * `:test:*` binding of anything else is a real binding, held to name a production adapter like any other.
     */
    fun isMockBinding(b: ContractCoverage.BindingDecl): Boolean {
        val adapter = b.adapter ?: return false
        if (adapter in adapters) return false
        if ("/adapter/generic/mock/src/" in b.file) return true
        if (!b.file.startsWith("/test/")) return false
        val module = b.file.split('/').take(3).joinToString("/") + "/"
        return adapter.startsWith("app.snapsync.mock.") || declaredIn(module, adapter.substringAfterLast('.'))
    }

    /** Whether a class named [simpleName] is declared in a source file under [module] — the binding's own test module. */
    private fun declaredIn(module: String, simpleName: String): Boolean {
        val declaration = Regex("""\b(?:class|object)\s+$simpleName\b""")
        return SourceScan.kotlinFiles().any { it.path.startsWith(module) && declaration.containsMatchIn(it.text) }
    }

    /**
     * The mocks: every named class of the mocks' module that implements a grid port, with the ports it implements — read
     * from its compiled classes, as the adapters are. An object a mock builds inline is part of its mock, not a mock.
     */
    val mocks: Map<String, Set<String>> by lazy {
        val root = java.io.File(
            Class.forName("app.snapsync.mock.MockDevice").protectionDomain.codeSource.location.toURI(),
        )
        val classes = BytecodeReader.read(listOf(root)).filterNot { it.local }
        val base = CodeBase(CodeTarget.JVM, classes + CodeBase.all.getValue(CodeTarget.JVM).classes.values)
        classes.filterNot {
            it.isInterface
        }.associate { c -> c.fqn to base.ancestors(c.fqn).filter { it in gridPorts }.toSet() }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Per port, the cells some real adapter (or, for the backend, the real `api/`) is proven to answer that no mock binding
     * ran — over the ports a mock binding's contract covers, so a mock is held to the system it stands in for (the backend
     * mock to the backend's cells, not to the transport it is reached through).
     */
    val mockGaps: Map<String, Set<String>> by lazy {
        val proven = (
            realCredits.map {
                it.cell
            } + ContractCoverage.coveredCells.filter { it.startsWith("${PortGrid.BACKEND}.") }
            ).toSet()
        val mockedClauses = ContractCatalog.contracts.flatMap { contract ->
            contract.clauses.filter { clause ->
                ContractCoverage.reachingBindings(
                    contract,
                    clause,
                ).any(::isMockBinding)
            }
        }
        val mocked = mockedClauses.flatMap { it.covers }.toSet()
        val held = mocked.map { it.substringBefore('.') }.toSet()
        (proven - mocked).filter { it.substringBefore('.') in held }.groupBy {
            it.substringBefore(
                '.',
            )
        }.mapValues { it.value.toSet() }
    }
}

/** What a claim resolves to: the claim, the grid cells it names, and why it is malformed, if it is. */
private typealias Resolved = Triple<Claim?, Set<String>, String?>

/** What a `@NeverAnswers` claim names: the claim the check reads, and the grid cells it stands for. */
private object ClaimResolution {
    /** The claim [member]/[answer]/[handler] states for [a], the grid cells it names, and why it is malformed if it is. */
    fun resolve(a: AdapterCoverage.Adapter, member: String, answer: String, handler: String): Resolved {
        val cells = a.ports.flatMap { AdapterCoverage.cellsByPort[it].orEmpty() }
        if (handler.isNotEmpty()) return resolveHandler(cells, member, answer, handler)
        if (answer.isEmpty()) return Triple(null, emptySet(), "a claim names an answer or a handler")
        return resolveAnswer(cells, member, answer)
    }

    /** A handler claim: the whole handler, or — with an [answer] — one argument's variant. */
    private fun resolveHandler(cells: List<PortGrid.Cell>, member: String, answer: String, handler: String): Resolved {
        if (member.isNotEmpty()) {
            return Triple(
                null,
                emptySet(),
                "a handler claim names the handler, and at most an argument",
            )
        }
        val all = cells.filter { ".handlers.$handler(" in it.text }
        val port = all.map { it.port }.distinct().singleOrNull()
            ?: return Triple(null, all.texts(), "names no single port's handler")
        val bundle = PortGrid.handlerBundle(AdapterCoverage.gridPorts.getValue("app.snapsync.ports.$port"))
            ?: return Triple(null, emptySet(), "$port has no handlers")
        val whole = Claim.Handler(bundle.qualifiedName!!, handler)
        if (answer.isEmpty()) return Triple(whole, all.texts() + handedOnlyBy(port, all), null)
        return resolveHandlerArgument(all, bundle, whole, answer)
    }

    /** One argument's variant of [whole]'s handler: the cells that pass it, never the handler's others. */
    private fun resolveHandlerArgument(
        all: List<PortGrid.Cell>,
        bundle: KClass<*>,
        whole: Claim.Handler,
        answer: String,
    ): Resolved {
        val handler = whole.field
        val args = { c: PortGrid.Cell -> c.text.substringAfter("$handler(").substringBeforeLast(')').split(", ") }
        val hits = all.filter { answer in args(it) }
        val index = hits.map { args(it).indexOf(answer) }.distinct().singleOrNull()
            ?: return Triple(null, hits.texts(), "\"$answer\" is no single argument of $handler")
        val field = bundle.memberProperties.first { it.name == handler }.returnType
        val type = field.arguments[index].type ?: return Triple(null, emptySet(), "$handler's argument $index has no type")
        return Triple(Claim.HandlerArgument(whole, answerOf(type.classifier as KClass<*>, answer)), hits.texts(), null)
    }

    /** A member's answer: a constant, or a variant of its answer type. */
    private fun resolveAnswer(cells: List<PortGrid.Cell>, member: String, answer: String): Resolved {
        val (portName, memberName) = if ('.' in member) member.substringBefore('.') to member.substringAfter('.') else null to member
        val hits = cells.filter { c ->
            isAnswerCell(c, answer) && (memberName.isEmpty() || c.member == memberName) && (portName == null || c.port == portName)
        }
        if (hits.isEmpty()) return Triple(null, emptySet(), null)
        val ports = hits.map { it.port }.distinct()
        if (memberName.isNotEmpty() && ports.size > 1) {
            return Triple(null, hits.texts(), "\"$memberName\" is a member of $ports: name it \"Port.$memberName\"")
        }
        val members = hits.map { it.member }.toSet()
        if (answer in CONSTANTS) {
            return if (memberName.isEmpty()) {
                Triple(null, hits.texts(), "a constant claim names its member")
            } else {
                Triple(Claim.Constant(members, answer), hits.texts(), null)
            }
        }
        val variant = hits.map {
            variantOf(
                AdapterCoverage.gridPorts.getValue("app.snapsync.ports.${it.port}"),
                it.member,
                answer,
            )
        }.distinct()
        val v = variant.singleOrNull() ?: return Triple(null, hits.texts(), "\"$answer\" names different types: $variant")
        return Triple(v.copy(members = members), hits.texts(), null)
    }

    /** A member's own answer cell (`Port.member → X`, not a handle's), whose one variant is [answer]. */
    private fun isAnswerCell(c: PortGrid.Cell, answer: String) =
        " → " in c.text && c.variants == listOf(answer) && c.text.substringBefore(" → ").count { it == '.' } == 1

    private fun List<PortGrid.Cell>.texts() = map { it.text }.toSet()

    /**
     * The cells of every handle [port] hands out through [handler] ALONE: a handler that is never called hands no handle,
     * so its handle's cells are never answered either. A handle another cell of the port also carries stays owed.
     */
    private fun handedOnlyBy(port: String, handler: List<PortGrid.Cell>): Set<String> {
        val cells = AdapterCoverage.cellsByPort["app.snapsync.ports.$port"].orEmpty()
        val handles = cells.map { it.text.substringAfter("$port.").substringBefore('.') }
            .filter { it.first().isUpperCase() }.toSet()
        return handles.filter { h ->
            val carriers = cells.filter { c -> !c.text.startsWith("$port.$h.") && Regex("""\b$h\b""").containsMatchIn(c.text) }
            carriers.isNotEmpty() && carriers.all { it in handler }
        }.flatMap { h -> cells.filter { it.text.startsWith("$port.$h.") }.map { it.text } }.toSet()
    }

    /** The class (or enum entry) [answer] names among [member]'s answers on [port]. */
    private fun variantOf(port: KClass<*>, member: String, answer: String): Claim.Answer {
        val type = (
            port.memberFunctions.firstOrNull { it.name == member }?.returnType
                ?: port.memberProperties.first { it.name == member }.returnType
            )
        return answerOf(PortGrid.bindingFor(type).classifier as KClass<*>, answer)
    }

    /** [answer] as a variant of [k]: its entry, for an enum; its named leaf, for a sealed type. */
    private fun answerOf(k: KClass<*>, answer: String): Claim.Answer = if (k.java.isEnum) {
        Claim.Answer(emptySet(), k.qualifiedName!!, answer.substringAfterLast('.'))
    } else {
        Claim.Answer(emptySet(), PortGrid.leaves(k).first { PortGrid.relativeName(it) == answer }.qualifiedName!!)
    }

    private val CONSTANTS = setOf("true", "false", "null", "returns")

    // ---- the gate's readings ---------------------------------------------------------------------------------------
}

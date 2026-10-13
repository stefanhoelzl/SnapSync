package app.snapsync.architecture

import java.io.File

/**
 * **Which clauses run against a real implementation on some host** (`docs/architecture.md`, "The contract-coverage
 * gate"): the one reading [ContractCoverageTest] fails on and [ClauseCoversTest] counts a cell COVERED through
 * ([coveredCells]).
 *
 * Contracts and their clauses are the catalog's ([ContractCatalog], by reflection). Bindings and recordings are read
 * from source text and the committed files, because the bindings live in test source sets of many modules —
 * Kotlin/Native ones among them — that no JVM classpath reaches:
 *  - every binding (`: Binding<State, Port>`), its `kind`, `host`, literal `reaches = setOf(...)` and the `adapter` it
 *    proves (`override val adapter = X::class`);
 *  - every recording (`test/contracts/recordings/<Name>@<HOST>[.<GRANT>|.<PRECONDITION>].rec`) and its `[CLAUSE_ID]`
 *    blocks — a binding that declares `override val grant = LibraryGrant.X` (or `override val precondition = "X"`)
 *    counts only through that file.
 *
 * A clause is REAL when a `Live` binding on a host CI runs declares its state reachable, or a `Replay` binding's
 * recording holds its id. A host some `Replay` binding names is a RECORDED host — CI never runs it — so a `Live`
 * binding there (the device's own binding, which records) is not coverage: only its recording is. A host CI runs
 * **in-app** — the simulator app — counts a `Live` binding only when the in-app registry the `journeys (ios)` job runs
 * names it: a `simulatorAppContract(<Contract>, <BindingClass>(), …)` call.
 *
 * Scope is derived, never listed ("Gates fail closed on novelty"), with ONE stated exclusion: the mechanism's own
 * self-tests under `test/contracts/src/commonTest`, whose toy bindings misdeclare on purpose to prove the runner
 * catches a lying binding. They are not port contracts and bind no port.
 */
internal object ContractCoverage {

    class BindingDecl(
        val file: String,
        val name: String?,
        val stateEnum: String,
        val kind: String?,
        val host: String?,
        val reaches: Set<String>?,
        val reachesRaw: String?,
        val grant: String?,
        /** The class the binding proves, as written (`X`), and resolved to its FQN; `null` where it names none. */
        val adapterName: String? = null,
        val adapter: String? = null,
    )

    private val sources = SourceScan.kotlinFiles().filterNot { "/test/contracts/src/commonTest/" in it.path }

    val bindings: List<BindingDecl> by lazy {
        sources.flatMap { src ->
            val starts = bindingStarts(src.text)
            starts.mapIndexed { i, m ->
                val body = src.text.substring(m.start, starts.getOrNull(i + 1)?.start ?: src.text.length)
                val raw = REACHES.find(body)?.groupValues?.get(1)
                val enum = m.stateEnum
                val adapterName = ADAPTER.find(body)?.groupValues?.get(1)
                val tokens = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                val parsed = tokens
                    ?.takeIf { t -> t.isNotEmpty() && t.all { STATE_REF.matches(it) && it.startsWith("$enum.") } }
                    ?.map { it.substringAfter('.') }?.toSet()
                BindingDecl(
                    src.path,
                    m.name,
                    m.stateEnum,
                    KIND.find(body)?.groupValues?.get(1),
                    HOST.find(body)?.groupValues?.get(1),
                    parsed,
                    raw,
                    GRANT.find(body)?.groupValues?.get(1) ?: PRECONDITION.find(body)?.groupValues?.get(1),
                    adapterName,
                    adapterName?.let { resolve(src.text, it) },
                )
            }
        }
    }

    /** The binding classes the simulator app's registry names — what the `journeys (ios)` job actually runs. */
    val registered: Set<String> by lazy {
        sources.flatMap { src -> REGISTERED.findAll(src.text).map { it.groupValues[1] }.toList() }.toSet()
    }

    val recordings: Map<String, Set<String>> by lazy {
        recordingsDir().listFiles { f -> f.extension == "rec" }.orEmpty().associate { f ->
            f.nameWithoutExtension to f.readLines().mapNotNull { BLOCK.matchEntire(it)?.groupValues?.get(1) }.toSet()
        }
    }

    /** Whether [clause] of [contract] runs against a real implementation on some host. */
    fun isReal(contract: ContractCatalog.ContractDecl, clause: ContractCatalog.ClauseDecl): Boolean =
        realBindings(contract, clause).isNotEmpty()

    /**
     * The bindings that run [clause] of [contract] against a real implementation: a `Live` one CI runs that reaches its
     * state, or a `Replay` one whose recording holds its block — what the per-adapter gate credits, each to its adapter.
     */
    fun realBindings(contract: ContractCatalog.ContractDecl, clause: ContractCatalog.ClauseDecl): List<BindingDecl> {
        val mine = bindings.filter { it.stateEnum == contract.stateEnum }
        val recordedHosts = mine.filter { it.kind == "Replay" }.mapNotNull { it.host }.toSet()
        return mine.filter { b ->
            val live = runsLiveOnCi(b, recordedHosts) && b.reaches.orEmpty().contains(clause.state)
            val replayed = b.kind == "Replay" && b.reaches.orEmpty().contains(clause.state) &&
                recordings[recordingKey(contract.name, b)].orEmpty().contains(clause.id)
            live || replayed
        }
    }

    /** Every binding of [contract] that reaches [clause]'s state, whatever its kind — a stand-in's run included. */
    fun reachingBindings(
        contract: ContractCatalog.ContractDecl,
        clause: ContractCatalog.ClauseDecl,
    ): List<BindingDecl> =
        bindings.filter { it.stateEnum == contract.stateEnum && it.reaches.orEmpty().contains(clause.state) }

    private class BindingStart(val start: Int, val name: String?, val stateEnum: String)

    /**
     * Where each binding opens: every `: Binding<State,` with the `object` or `class Name` that declares it — the nearest
     * one before it, so a constructor taking a function type (`() -> Unit`) is read too.
     */
    private fun bindingStarts(text: String): List<BindingStart> = BINDING_TYPE.findAll(text).mapNotNull { m ->
        val head = DECLARATION.findAll(text.substring(0, m.range.first)).lastOrNull() ?: return@mapNotNull null
        // Only the declaration's own parameter list may stand between it and the supertype — not a comment's prose.
        val between = text.substring(head.range.last + 1, m.range.first).trim()
        if (between.isNotEmpty() && !isParameterList(between)) return@mapNotNull null
        BindingStart(head.range.first, head.groupValues[1].takeIf { it.isNotEmpty() }, m.groupValues[1])
    }.toList()

    /** Whether [s] is one parenthesized list: it opens with `(` and closes, balanced, only at its end. */
    private fun isParameterList(s: String) = s.startsWith("(") && s.endsWith(")") && balanced(s)

    /** Whether [s] closes every parenthesis it opens, and only at its end. */
    private fun balanced(s: String): Boolean {
        var depth = 0
        s.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && i < s.length - 1) return false
        }
        return depth == 0
    }

    /** [name]'s FQN as the file [text] sees it: an import of it, a class nested in the file's own class, or its package's. */
    private fun resolve(text: String, name: String): String {
        val head = name.substringBefore('.')
        val tail = name.removePrefix(head)
        Regex("""^import ([\w.]+)\.$head$""", RegexOption.MULTILINE).find(text)?.let {
            return "${it.groupValues[1]}.$head$tail"
        }
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        val nested = Regex("""^\s+(?:private |internal )?(?:inner )?(?:class|object) $name\b""", RegexOption.MULTILINE)
        val outer = TOP_CLASS.find(text)?.groupValues?.get(1)
        return if (outer != null && nested.containsMatchIn(text)) "$pkg.$outer.$name" else "$pkg.$name"
    }

    /**
     * The grid cells COVERED: declared OUTRIGHT by a clause that [isReal] — a cell in a clause's one-of group is a weak
     * claim, held apart in `oneOf`. The one reading `ClauseCoversTest` holds every grid cell to — a claim that should
     * count for less (a mock-only one, a weak one) is excluded here and nowhere else.
     */
    val coveredCells: Set<String> by lazy {
        ContractCatalog.contracts.flatMap { c -> c.clauses.filter { isReal(c, it) }.flatMap { it.covers } }.toSet()
    }

    /** Whether [b] is a `Live` binding CI runs: not on a recorded host, and registered if its host runs in-app. */
    private fun runsLiveOnCi(b: BindingDecl, recordedHosts: Set<String>) =
        b.kind == "Live" && b.host !in recordedHosts && (b.host !in IN_APP_CI_HOSTS || b.name in registered)

    /** The recording a binding counts through: `<Contract>@<HOST>`, suffixed `.<GRANT>` or `.<PRECONDITION>` where it declares one. */
    fun recordingKey(contract: String, b: BindingDecl): String =
        "$contract@${b.host?.removePrefix("Host.")}" + (b.grant?.let { ".$it" } ?: "")

    fun recordingsDir() = File(SourceScan.repoRoot, "test/contracts/recordings")

    /** Hosts CI runs inside the app, where only the in-app registry proves a binding is run at all. */
    val IN_APP_CI_HOSTS = setOf("Host.IOS_SIM_APP")

    private val BINDING_TYPE = Regex(""":\s*Binding<(\w+),""")
    private val DECLARATION = Regex("""\b(?:object|class\s+(\w+))\b""")
    private val ADAPTER = Regex("""override val adapter\s*=\s*([\w.]+)::class""")
    private val PACKAGE = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
    private val TOP_CLASS =
        Regex("""^(?:internal |private )?(?:abstract |open )?(?:class|object) (\w+)""", RegexOption.MULTILINE)
    private val REGISTERED = Regex("""simulatorAppContract\(\s*\w+\s*,\s*(\w+)\(""")
    private val REACHES = Regex("""override val reaches\s*=\s*setOf\(([^)]*)\)""")
    private val KIND = Regex("""override val kind\s*=\s*BindingKind\.(\w+)""")
    private val HOST = Regex("""override val host\s*=\s*(Host\.\w+|currentHost)""")
    private val GRANT = Regex("""override val grant\s*=\s*LibraryGrant\.(\w+)""")
    private val PRECONDITION = Regex("""override val precondition\s*=\s*"(\w+)"""")
    private val STATE_REF = Regex("""\w+\.\w+""")
    private val BLOCK = Regex("""\[(.+)]""")
}

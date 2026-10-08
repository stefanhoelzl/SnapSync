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
 *  - every binding (`: Binding<State, Port>`), its `kind`, `host` and literal `reaches = setOf(...)`;
 *  - every recording (`test/contracts/recordings/<Name>@<HOST>[.<GRANT>|.<PRECONDITION>].rec`) and its `[CLAUSE_ID]`
 *    blocks — a binding that declares `override val grant = GalleryAccess.X` (or `override val precondition = "X"`)
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
    )

    private val sources = SourceScan.kotlinFiles().filterNot { "/test/contracts/src/commonTest/" in it.path }

    val bindings: List<BindingDecl> by lazy {
        sources.flatMap { src ->
            val starts = BINDING.findAll(src.text).toList()
            starts.mapIndexed { i, m ->
                val body = src.text.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: src.text.length)
                val raw = REACHES.find(body)?.groupValues?.get(1)
                val enum = m.groupValues[2]
                val tokens = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                val parsed = tokens
                    ?.takeIf { t -> t.isNotEmpty() && t.all { STATE_REF.matches(it) && it.startsWith("$enum.") } }
                    ?.map { it.substringAfter('.') }?.toSet()
                BindingDecl(
                    src.path,
                    m.groupValues[1].takeIf { it.isNotEmpty() },
                    m.groupValues[2],
                    KIND.find(body)?.groupValues?.get(1),
                    HOST.find(body)?.groupValues?.get(1),
                    parsed,
                    raw,
                    GRANT.find(body)?.groupValues?.get(1) ?: PRECONDITION.find(body)?.groupValues?.get(1),
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
    fun isReal(contract: ContractCatalog.ContractDecl, clause: ContractCatalog.ClauseDecl): Boolean {
        val mine = bindings.filter { it.stateEnum == contract.stateEnum }
        val recordedHosts = mine.filter { it.kind == "Replay" }.mapNotNull { it.host }.toSet()
        val live = mine.any { runsLiveOnCi(it, recordedHosts) && it.reaches.orEmpty().contains(clause.state) }
        val replayed = mine.any { b ->
            b.kind == "Replay" && b.reaches.orEmpty().contains(clause.state) &&
                recordings[recordingKey(contract.name, b)].orEmpty().contains(clause.id)
        }
        return live || replayed
    }

    /**
     * The grid cells COVERED: declared OUTRIGHT by a clause that [isReal] — a cell in a clause's one-of group is a weak
     * claim, held apart in `oneOf`. The one reading the open-cells list
     * ([openCellsFile]) is held to — a claim that should count for less (a mock-only one, a weak one) is excluded here
     * and nowhere else.
     */
    val coveredCells: Set<String> by lazy {
        ContractCatalog.contracts.flatMap { c -> c.clauses.filter { isReal(c, it) }.flatMap { it.covers } }.toSet()
    }

    /** The committed list of the grid cells not yet [coveredCells]: one per line, sorted, `#` lines comments. */
    fun openCellsFile() = File(SourceScan.repoRoot, "test/contracts/open-cells.txt")

    /** Whether [b] is a `Live` binding CI runs: not on a recorded host, and registered if its host runs in-app. */
    private fun runsLiveOnCi(b: BindingDecl, recordedHosts: Set<String>) =
        b.kind == "Live" && b.host !in recordedHosts && (b.host !in IN_APP_CI_HOSTS || b.name in registered)

    /** The recording a binding counts through: `<Contract>@<HOST>`, suffixed `.<GRANT>` or `.<PRECONDITION>` where it declares one. */
    fun recordingKey(contract: String, b: BindingDecl): String =
        "$contract@${b.host?.removePrefix("Host.")}" + (b.grant?.let { ".$it" } ?: "")

    fun recordingsDir() = File(SourceScan.repoRoot, "test/contracts/recordings")

    /** Hosts CI runs inside the app, where only the in-app registry proves a binding is run at all. */
    val IN_APP_CI_HOSTS = setOf("Host.IOS_SIM_APP")

    private val BINDING = Regex("""(?:object|class\s+(\w+)\s*(?:\([^)]*\))?)\s*:\s*Binding<(\w+),""")
    private val REGISTERED = Regex("""simulatorAppContract\(\s*\w+\s*,\s*(\w+)\(""")
    private val REACHES = Regex("""override val reaches\s*=\s*setOf\(([^)]*)\)""")
    private val KIND = Regex("""override val kind\s*=\s*BindingKind\.(\w+)""")
    private val HOST = Regex("""override val host\s*=\s*(Host\.\w+|currentHost)""")
    private val GRANT = Regex("""override val grant\s*=\s*GalleryAccess\.(\w+)""")
    private val PRECONDITION = Regex("""override val precondition\s*=\s*"(\w+)"""")
    private val STATE_REF = Regex("""\w+\.\w+""")
    private val BLOCK = Regex("""\[(.+)]""")
}

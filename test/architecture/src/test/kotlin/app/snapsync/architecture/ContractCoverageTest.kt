package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every port-contract clause runs against a real implementation on some host** (capability
 * `docs/architecture.md`, "The contract-coverage gate"; the rule itself: `docs/architecture.md`, "Every clause runs
 * against a real implementation on some host").
 *
 * A clause only the honest fake ever runs looks verified while nothing has compared it with reality. So the
 * gate derives, from source text and the committed recordings:
 *  - every contract (an `object` extending `Contract`, with the name it passes) and its `clause(...)` calls;
 *  - every binding (`: Binding<State, Port>`), its `kind`, `host` and literal `reaches = setOf(...)`;
 *  - every recording (`test/contracts/recordings/<Name>@<HOST>[.<GRANT>].rec`) and its `[CLAUSE_ID]` blocks —
 *    a binding that declares `override val grant = PermissionStatus.X` counts only through its grant's file;
 *
 * and fails any clause whose state no `Live` binding on a host CI runs declares reachable, and whose id no
 * `Replay` binding's recording holds. A host some `Replay` binding names is a RECORDED host — CI never runs it
 * — so a `Live` binding there (the device's own binding, which records) is not coverage: only its recording is.
 * Otherwise a device-only clause would count as covered the day its binding was written, before anyone ran it. That also closes the escape hatch: declaring a failing clause's state unreachable on its
 * only real host leaves the clause uncovered, and this fails.
 *
 * A host CI runs **in-app** — the simulator app — is visible here only through source, so a `Live` binding there
 * counts only when the in-app registry the `ios-contracts` job runs names it: a `simulatorAppContract(<Contract>,
 * <BindingClass>(), …)` call. An unregistered one is run by nobody, and the gate fails naming it.
 *
 * Scope is derived, never listed ("Gates fail closed on novelty"), with ONE stated exclusion: the mechanism's
 * own self-tests under `test/contracts/src/commonTest`, whose toy bindings misdeclare on purpose to prove the
 * runner catches a lying binding. They are not port contracts and bind no port.
 */
class ContractCoverageTest {

    private val sources = SourceScan.kotlinFiles().filterNot { "/test/contracts/src/commonTest/" in it.path }

    private class ContractDecl(val name: String, val stateEnum: String, val clauses: List<Pair<String, String>>, val file: String)

    private class BindingDecl(
        val file: String,
        val name: String?,
        val stateEnum: String,
        val kind: String?,
        val host: String?,
        val reaches: Set<String>?,
        val reachesRaw: String?,
        val grant: String?,
    )

    private val contracts: List<ContractDecl> = sources.flatMap { src ->
        CONTRACT.findAll(src.text).map { m ->
            val enum = m.groupValues[1]
            val clauses = CLAUSE.findAll(src.text)
                .filter { it.groupValues[2] == enum }
                .map { it.groupValues[1].replace("\\\"", "\"") to it.groupValues[3] }
                .toList()
            ContractDecl(m.groupValues[2], enum, clauses, src.path)
        }.toList()
    }

    private val bindings: List<BindingDecl> = sources.flatMap { src ->
        val starts = BINDING.findAll(src.text).toList()
        starts.mapIndexed { i, m ->
            val body = src.text.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: src.text.length)
            val raw = REACHES.find(body)?.groupValues?.get(1)
            val enum = m.groupValues[2]
            val tokens = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val parsed = tokens?.takeIf { t -> t.isNotEmpty() && t.all { STATE_REF.matches(it) && it.startsWith("$enum.") } }
                ?.map { it.substringAfter('.') }?.toSet()
            BindingDecl(
                src.path, m.groupValues[1].takeIf { it.isNotEmpty() }, m.groupValues[2], KIND.find(body)?.groupValues?.get(1),
                HOST.find(body)?.groupValues?.get(1), parsed, raw, GRANT.find(body)?.groupValues?.get(1),
            )
        }
    }

    /** The binding classes the simulator app's registry names — what the `ios-contracts` job actually runs. */
    private val registered: Set<String> = sources.flatMap { src ->
        REGISTERED.findAll(src.text).map { it.groupValues[1] }.toList()
    }.toSet()

    /** Whether [b] is a `Live` binding CI runs: not on a recorded host, and registered if its host runs in-app. */
    private fun runsLiveOnCi(b: BindingDecl, recordedHosts: Set<String>) =
        b.kind == "Live" && b.host !in recordedHosts && (b.host !in IN_APP_CI_HOSTS || b.name in registered)

    private val recordings: Map<String, Set<String>> = recordingsDir().listFiles { f -> f.extension == "rec" }
        .orEmpty()
        .associate { f -> f.nameWithoutExtension to f.readLines().mapNotNull { BLOCK.matchEntire(it)?.groupValues?.get(1) }.toSet() }

    @Test
    fun `every clause is reached by a real implementation on some host`() {
        val uncovered = contracts.flatMap { contract ->
            val mine = bindings.filter { it.stateEnum == contract.stateEnum }
            val recordedHosts = mine.filter { it.kind == "Replay" }.mapNotNull { it.host }.toSet()
            contract.clauses.filter { (id, state) ->
                val live = mine.any { runsLiveOnCi(it, recordedHosts) && it.reaches.orEmpty().contains(state) }
                val replayed = mine.any { b ->
                    b.kind == "Replay" && b.reaches.orEmpty().contains(state) &&
                        recordings[recordingKey(contract.name, b)].orEmpty().contains(id)
                }
                !live && !replayed
            }.map { (id, state) -> "${contract.name} / $id (state $state) — ${contract.file}" }
        }
        if (uncovered.isNotEmpty()) {
            fail(
                "these clauses run against no real implementation on any host — only a fake would ever run them. " +
                    "Bind a host that reaches the state, record it on a device, or move the claim out of the " +
                    "contract (a platform fact belongs in the adapter's docs; our own logic in a fake-backed test):\n  " +
                    uncovered.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every binding declares kind, host and reachable states in a form this gate can read`() {
        val unreadable = bindings.filter { it.kind == null || it.host == null || it.reaches == null }
            .map { "${it.file}: kind=${it.kind} host=${it.host} reaches=${it.reachesRaw?.trim()}" }
        if (unreadable.isNotEmpty()) {
            fail(
                "a binding must write `override val kind = BindingKind.X`, `override val host = Host.X` (or " +
                    "`currentHost`) and `override val reaches = setOf(State.A, State.B)` literally — a computed " +
                    "declaration would let coverage be claimed without being readable:\n  " + unreadable.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every binding on an in-app CI host is registered for the job that runs it`() {
        val unregistered = bindings.filter { it.host in IN_APP_CI_HOSTS && it.name !in registered }
            .map { "${it.name ?: "<anonymous object>"} (${it.host}) — ${it.file}" }
        if (unregistered.isNotEmpty()) {
            fail(
                "these bindings name a host the `ios-contracts` job runs in-app, but no `simulatorAppContract(<Contract>, " +
                    "<BindingClass>(), …)` registers them, so nothing ever runs them. Register each as a named class:\n  " +
                    unregistered.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every host is named by some binding`() {
        val declared = HOST_ENUM.find(read(HOST_FILE))?.groupValues?.get(1)
            ?.lines()?.mapNotNull { HOST_ENTRY.matchEntire(it)?.groupValues?.get(1) }.orEmpty()
        assertTrue(declared.isNotEmpty(), "found no entries in the Host enum at $HOST_FILE")
        val named = bindings.mapNotNull { it.host?.takeIf { h -> h.startsWith("Host.") }?.removePrefix("Host.") }.toSet()
        val unused = declared - named
        assertTrue(unused.isEmpty(), "Host values no binding names — the enum holds only bound hosts: $unused")
    }

    @Test
    fun `every recording names a contract and a host that exist`() {
        val names = contracts.map { it.name }.toSet()
        val bad = recordings.keys.filter { key -> key.substringBefore('@') !in names || '@' !in key }
        assertTrue(bad.isEmpty(), "recordings named for no contract (expected <Contract>@<HOST>[.<GRANT>].rec): $bad")
    }

    @Test
    fun `every grant a recording is named for is declared by a binding of that contract and host`() {
        val expected = contracts.flatMap { c ->
            bindings.filter { it.stateEnum == c.stateEnum && it.kind == "Replay" }.map { recordingKey(c.name, it) }
        }.toSet()
        val undeclared = recordings.keys.filter { key -> '.' in key.substringAfter('@') && key !in expected }
        assertTrue(
            undeclared.isEmpty(),
            "recordings carry a grant no Replay binding of that contract and host declares (`override val grant = " +
                "PermissionStatus.X`), so nothing replays them: $undeclared",
        )
    }

    // ---- non-vacuity: one twin per derived group -------------------------------------------------------

    @Test
    fun `the scan finds contracts and their clauses`() {
        assertTrue(contracts.isNotEmpty(), "no contract object found — the declaration form moved")
        assertTrue(contracts.all { it.clauses.isNotEmpty() }, "a contract with no parsed clause: ${contracts.filter { it.clauses.isEmpty() }.map { it.name }}")
    }

    @Test
    fun `the scan finds live bindings`() {
        assertTrue(bindings.any { it.kind == "Live" }, "no `Live` binding found — the binding form moved")
    }

    @Test
    fun `the scan finds the simulator app's registry`() {
        assertTrue(
            registered.isNotEmpty() && bindings.any { it.host in IN_APP_CI_HOSTS && it.name in registered },
            "no registered simulator-app binding found — the registry's form moved, or it emptied",
        )
    }

    @Test
    fun `the scan finds recordings with blocks`() {
        assertTrue(
            recordings.values.any { it.isNotEmpty() },
            "no recording with a [CLAUSE_ID] block under ${recordingsDir()} — a Replay binding covers nothing without one",
        )
    }

    /** The recording a binding counts through: `<Contract>@<HOST>`, suffixed `.<GRANT>` where it declares one. */
    private fun recordingKey(contract: String, b: BindingDecl): String =
        "$contract@${b.host?.removePrefix("Host.")}" + (b.grant?.let { ".$it" } ?: "")

    private fun recordingsDir() = File(SourceScan.repoRoot, "test/contracts/recordings")

    private fun read(path: String) = File(SourceScan.repoRoot, path).readText()

    private companion object {
        const val HOST_FILE = "test/contracts/src/commonMain/kotlin/app/snapsync/contracts/Host.kt"
        val CONTRACT = Regex("""object\s+\w+\s*:\s*Contract<(\w+),\s*[\w.<>, ]+>\("([^"]+)"\)""")
        val CLAUSE = Regex("""clause\("((?:[^"\\]|\\.)*)",\s*(\w+)\.(\w+)\)""")
        val BINDING = Regex("""(?:object|class\s+(\w+)\s*(?:\([^)]*\))?)\s*:\s*Binding<(\w+),""")
        val REGISTERED = Regex("""simulatorAppContract\(\s*\w+\s*,\s*(\w+)\(""")

        /** Hosts CI runs inside the app, where only the in-app registry proves a binding is run at all. */
        val IN_APP_CI_HOSTS = setOf("Host.IOS_SIM_APP")
        val REACHES = Regex("""override val reaches\s*=\s*setOf\(([^)]*)\)""")
        val KIND = Regex("""override val kind\s*=\s*BindingKind\.(\w+)""")
        val HOST = Regex("""override val host\s*=\s*(Host\.\w+|currentHost)""")
        val GRANT = Regex("""override val grant\s*=\s*PermissionStatus\.(\w+)""")
        val STATE_REF = Regex("""\w+\.\w+""")
        val BLOCK = Regex("""\[(.+)]""")
        val HOST_ENUM = Regex("""enum class Host \{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL)
        val HOST_ENTRY = Regex("""\s*([A-Z][A-Z0-9_]*),?\s*""")
    }
}

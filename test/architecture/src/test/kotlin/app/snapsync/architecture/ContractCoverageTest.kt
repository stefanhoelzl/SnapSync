package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every port-contract clause runs against a real implementation on some host** (capability
 * `architecture-guards`, "The contract-coverage gate"; the rule itself: `port-contracts`, "Every clause runs
 * against a real implementation on some host").
 *
 * A clause only the honest fake ever runs looks verified while nothing has compared it with reality. So the
 * gate derives, from source text and the committed recordings:
 *  - every contract (an `object` extending `Contract`, with the name it passes) and its `clause(...)` calls;
 *  - every binding (`: Binding<State, Port>`), its `kind`, `host` and literal `reaches = setOf(...)`;
 *  - every recording (`test/contracts/recordings/<Name>@<HOST>.rec`) and its `[CLAUSE_ID]` blocks;
 *
 * and fails any clause whose state no `Live` binding on a host CI runs declares reachable, and whose id no
 * `Replay` binding's recording holds. A host some `Replay` binding names is a RECORDED host — CI never runs it
 * — so a `Live` binding there (the device's own binding, which records) is not coverage: only its recording is.
 * Otherwise a device-only clause would count as covered the day its binding was written, before anyone ran it. That also closes the escape hatch: declaring a failing clause's state unreachable on its
 * only real host leaves the clause uncovered, and this fails.
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
        val stateEnum: String,
        val kind: String?,
        val host: String?,
        val reaches: Set<String>?,
        val reachesRaw: String?,
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
            val enum = m.groupValues[1]
            val tokens = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val parsed = tokens?.takeIf { t -> t.isNotEmpty() && t.all { STATE_REF.matches(it) && it.startsWith("$enum.") } }
                ?.map { it.substringAfter('.') }?.toSet()
            BindingDecl(src.path, enum, KIND.find(body)?.groupValues?.get(1), HOST.find(body)?.groupValues?.get(1), parsed, raw)
        }
    }

    private val recordings: Map<String, Set<String>> = recordingsDir().listFiles { f -> f.extension == "rec" }
        .orEmpty()
        .associate { f -> f.nameWithoutExtension to f.readLines().mapNotNull { BLOCK.matchEntire(it)?.groupValues?.get(1) }.toSet() }

    @Test
    fun `every clause is reached by a real implementation on some host`() {
        val uncovered = contracts.flatMap { contract ->
            val mine = bindings.filter { it.stateEnum == contract.stateEnum }
            val recordedHosts = mine.filter { it.kind == "Replay" }.mapNotNull { it.host }.toSet()
            contract.clauses.filter { (id, state) ->
                val live = mine.any { it.kind == "Live" && it.host !in recordedHosts && it.reaches.orEmpty().contains(state) }
                val replayed = mine.any { b ->
                    b.kind == "Replay" && b.reaches.orEmpty().contains(state) &&
                        recordings["${contract.name}@${b.host?.removePrefix("Host.")}"].orEmpty().contains(id)
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
        assertTrue(bad.isEmpty(), "recordings named for no contract (expected <Contract>@<HOST>.rec): $bad")
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
    fun `the scan finds recordings with blocks`() {
        assertTrue(
            recordings.values.any { it.isNotEmpty() },
            "no recording with a [CLAUSE_ID] block under ${recordingsDir()} — a Replay binding covers nothing without one",
        )
    }

    private fun recordingsDir() = File(SourceScan.repoRoot, "test/contracts/recordings")

    private fun read(path: String) = File(SourceScan.repoRoot, path).readText()

    private companion object {
        const val HOST_FILE = "test/contracts/src/commonMain/kotlin/app/snapsync/contracts/Host.kt"
        val CONTRACT = Regex("""object\s+\w+\s*:\s*Contract<(\w+),\s*[\w.<>, ]+>\("([^"]+)"\)""")
        val CLAUSE = Regex("""clause\("((?:[^"\\]|\\.)*)",\s*(\w+)\.(\w+)\)""")
        val BINDING = Regex("""(?:object|class\s+\w+\s*(?:\([^)]*\))?)\s*:\s*Binding<(\w+),""")
        val REACHES = Regex("""override val reaches\s*=\s*setOf\(([^)]*)\)""")
        val KIND = Regex("""override val kind\s*=\s*BindingKind\.(\w+)""")
        val HOST = Regex("""override val host\s*=\s*(Host\.\w+|currentHost)""")
        val STATE_REF = Regex("""\w+\.\w+""")
        val BLOCK = Regex("""\[(.+)]""")
        val HOST_ENUM = Regex("""enum class Host \{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL)
        val HOST_ENTRY = Regex("""\s*([A-Z][A-Z0-9_]*),?\s*""")
    }
}

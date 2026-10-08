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
 * A clause only the honest fake ever runs looks verified while nothing has compared it with reality. So this fails any
 * clause that is not REAL by [ContractCoverage]'s reading — no `Live` binding on a host CI runs declares its state
 * reachable, and no `Replay` binding's recording holds its id. That also closes the escape hatch: declaring a failing
 * clause's state unreachable on its only real host leaves the clause uncovered, and this fails. The contracts and
 * their clauses are [ContractCatalog]'s, loaded as values.
 */
class ContractCoverageTest {

    private val contracts = ContractCatalog.contracts
    private val bindings = ContractCoverage.bindings
    private val registered = ContractCoverage.registered
    private val recordings = ContractCoverage.recordings

    @Test
    fun `every clause is reached by a real implementation on some host`() {
        val uncovered = contracts.flatMap { contract ->
            contract.clauses.filterNot { ContractCoverage.isReal(contract, it) }
                .map { "${contract.name} / ${it.id} (state ${it.state}) — ${contract.objectName}" }
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
        val unregistered = bindings.filter { it.host in ContractCoverage.IN_APP_CI_HOSTS && it.name !in registered }
            .map { "${it.name ?: "<anonymous object>"} (${it.host}) — ${it.file}" }
        if (unregistered.isNotEmpty()) {
            fail(
                "these bindings name a host the `journeys (ios)` job runs in-app, but no `simulatorAppContract(<Contract>, " +
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
        val literal = bindings.mapNotNull {
            it.host?.takeIf { h ->
                h.startsWith(
                    "Host.",
                )
            }?.removePrefix("Host.")
        }.toSet()
        // A shared binding (`override val host = currentHost`, the fakes' in `commonTest`) names every host a
        // `currentHost` actual answers, though CI runs those fakes on the JVM only.
        val shared = if (bindings.any { it.host == "currentHost" }) currentHostActuals() else emptySet()
        val unused = declared - literal - shared
        assertTrue(unused.isEmpty(), "Host values no binding names — the enum holds only bound hosts: $unused")
    }

    @Test
    fun `every recording names a contract and a host that exist`() {
        val names = contracts.map { it.name }.toSet()
        val bad = recordings.keys.filter { key -> key.substringBefore('@') !in names || '@' !in key }
        assertTrue(
            bad.isEmpty(),
            "recordings named for no contract (expected <Contract>@<HOST>[.<GRANT>|.<PRECONDITION>].rec): $bad",
        )
    }

    @Test
    fun `every grant a recording is named for is declared by a binding of that contract and host`() {
        val expected = contracts.flatMap { c ->
            bindings.filter { it.stateEnum == c.stateEnum && it.kind == "Replay" }.map { ContractCoverage.recordingKey(c.name, it) }
        }.toSet()
        val undeclared = recordings.keys.filter { key -> '.' in key.substringAfter('@') && key !in expected }
        assertTrue(
            undeclared.isEmpty(),
            "recordings carry a grant or precondition no Replay binding of that contract and host declares (`override " +
                "val grant = GalleryAccess.X` / `override val precondition = \"X\"`), so nothing replays them: $undeclared",
        )
    }

    // ---- non-vacuity: one twin per derived group -------------------------------------------------------

    @Test
    fun `the scan finds contracts and their clauses`() {
        assertTrue(contracts.isNotEmpty(), "no contract object found — the declaration form moved")
        assertTrue(
            contracts.all { it.clauses.isNotEmpty() },
            "a contract with no parsed clause: ${contracts.filter { it.clauses.isEmpty() }.map { it.name }}",
        )
    }

    @Test
    fun `the scan finds live bindings`() {
        assertTrue(bindings.any { it.kind == "Live" }, "no `Live` binding found — the binding form moved")
    }

    @Test
    fun `the scan finds the simulator app's registry`() {
        assertTrue(
            registered.isNotEmpty() && bindings.any { it.host in ContractCoverage.IN_APP_CI_HOSTS && it.name in registered },
            "no registered simulator-app binding found — the registry's form moved, or it emptied",
        )
    }

    @Test
    fun `the scan finds recordings with blocks`() {
        assertTrue(
            recordings.values.any { it.isNotEmpty() },
            "no recording with a [CLAUSE_ID] block under ${ContractCoverage.recordingsDir()} — a Replay binding covers nothing without one",
        )
    }

    /** Every host a platform's `currentHost` actual can answer, read off its source. */
    private fun currentHostActuals(): Set<String> =
        File(SourceScan.repoRoot, "test/contracts/src").walkTopDown()
            .filter { it.isFile && it.name == "CurrentHost.kt" && "/commonMain/" !in it.path }
            .flatMap { HOST_REF.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()

    private fun read(path: String) = File(SourceScan.repoRoot, path).readText()

    private companion object {
        const val HOST_FILE = "test/contracts/src/commonMain/kotlin/app/snapsync/contracts/Host.kt"
        val HOST_ENUM = Regex("""enum class Host \{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL)
        val HOST_ENTRY = Regex("""\s*([A-Z][A-Z0-9_]*),?\s*""")
        val HOST_REF = Regex("""Host\.([A-Z][A-Z0-9_]*)""")
    }
}

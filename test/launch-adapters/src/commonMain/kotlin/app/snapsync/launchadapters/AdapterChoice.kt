package app.snapsync.launchadapters

import app.snapsync.mock.MockedSystem

/**
 * A launch's choice: which systems are MOCK — every other one is REAL. Read by every process of a rig build at its start
 * (`LaunchAdapters`), so the app — whatever started it — and the upload extension compose over the same systems.
 */
class AdapterChoice(mocked: Set<MockedSystem>) {
    val mocked: Set<MockedSystem> = mocked.toSet()

    fun isMocked(system: MockedSystem): Boolean = system in mocked

    /**
     * Why this adapter choice cannot compose, one reason per broken rule — empty for a coherent choice. Each rule is a pair of
     * systems whose real half would reach something its mocked half cannot answer, or would let a mocked run touch the
     * real `snap-sync-dev` zone, which real users' photos live in.
     */
    fun incoherence(): List<String> = RULES.mapNotNull { it.brokenBy(this) }

    /** The adapters file's text: every system, one `key=mock|real` line each, in the vocabulary's order. */
    fun render(): String = MockedSystem.entries.joinToString("\n", postfix = "\n") {
        "${it.key}=${if (it in mocked) MOCK else REAL}"
    }

    override fun equals(other: Any?): Boolean = other is AdapterChoice && other.mocked == mocked

    override fun hashCode(): Int = mocked.hashCode()

    override fun toString(): String =
        if (mocked.isEmpty()) "all real" else "mocked: " + MockedSystem.entries.filter { it in mocked }.joinToString(",") { it.key }

    companion object {
        const val MOCK: String = "mock"
        const val REAL: String = "real"

        val ALL_REAL: AdapterChoice = AdapterChoice(emptySet())
        val ALL_MOCK: AdapterChoice = AdapterChoice(MockedSystem.entries.toSet())

        /**
         * Parse an adapters file: one `key=mock|real` per line, blank lines and `#` comments ignored, a missing system REAL.
         * An unknown system, an unknown value, a line that is not an assignment or a system named twice makes the
         * whole file [AdapterParse.Invalid] — never a guess: a typo that silently left a system real is exactly the run
         * that believes itself mocked while it reaches a real one.
         */
        fun parse(text: String): AdapterParse {
            val problems = mutableListOf<String>()
            val seen = mutableMapOf<MockedSystem, Boolean>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val where = "line ${index + 1} ('${raw.trim()}')"
                val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                val value = line.substringAfter('=', missingDelimiterValue = "").trim()
                val system = MockedSystem.ofKey(key)
                when {
                    '=' !in line -> problems += "$where is not a `system=mock|real` assignment"
                    system == null -> problems += "$where names no system; the systems are ${keys()}"
                    value != MOCK && value != REAL -> problems += "$where must be `$MOCK` or `$REAL`, was '$value'"
                    system in seen -> problems += "$where names ${system.key} a second time"
                    else -> seen[system] = value == MOCK
                }
            }
            return if (problems.isEmpty()) {
                AdapterParse.Parsed(AdapterChoice(seen.filterValues { it }.keys))
            } else {
                AdapterParse.Invalid(problems)
            }
        }

        private fun keys(): String = MockedSystem.entries.joinToString("|") { it.key }
    }
}

/** What an adapters file parsed to. */
sealed interface AdapterParse {
    class Parsed(val choice: AdapterChoice) : AdapterParse

    class Invalid(val problems: List<String>) : AdapterParse
}

/** A coherence rule: when [whenSystem] is [whenMocked], each of [then] must be [thenMocked] — because [why]. */
private class Rule(
    val whenSystem: MockedSystem,
    val whenMocked: Boolean,
    val then: List<MockedSystem>,
    val thenMocked: Boolean,
    val why: String,
) {
    fun brokenBy(choice: AdapterChoice): String? {
        if (choice.isMocked(whenSystem) != whenMocked) return null
        val broken = then.filter { choice.isMocked(it) != thenMocked }
        if (broken.isEmpty()) return null
        return "${whenSystem.key}=${state(whenMocked)} needs " +
            broken.joinToString(", ") { "${it.key}=${state(thenMocked)}" } + ": $why"
    }

    private fun state(mocked: Boolean) = if (mocked) AdapterChoice.MOCK else AdapterChoice.REAL
}

private fun mocked(system: MockedSystem, vararg then: MockedSystem, why: String) =
    Rule(system, whenMocked = true, then = then.toList(), thenMocked = true, why = why)

/**
 * The coherence rules (`docs/testing.md`, "Launch-time adapters"). Each names the one fact that forces it; a pair
 * this list does not name may be chosen freely.
 */
private val RULES: List<Rule> = listOf(
    mocked(
        MockedSystem.BACKEND, MockedSystem.UPLOAD_QUEUE, MockedSystem.UPLOAD_SESSION,
        why = "a real background transfer performs a real HTTP request, and the backend mock lives in the app's memory",
    ),
    mocked(
        MockedSystem.BACKEND, MockedSystem.DOWNLOADS,
        why = "the backend mock serves its union at synthetic URLs no real download session can fetch",
    ),
    mocked(
        MockedSystem.BACKEND, MockedSystem.PUSH,
        why = "a real push service only carries the real backend's pushes; the backend mock records the pushes it " +
            "would send, and only the push service's mock delivers them",
    ),
    mocked(
        MockedSystem.BACKEND, MockedSystem.INTEGRITY,
        why = "the backend mock mints only for the in-memory Secure Enclave's proofs, never for a real App Attest key",
    ),
    mocked(
        MockedSystem.INTEGRITY, MockedSystem.BACKEND,
        why = "the real backend verifies a genuine App Attest attestation, which the mocked Secure Enclave cannot make",
    ),
    mocked(
        MockedSystem.UPLOAD_QUEUE, MockedSystem.BACKEND,
        why = "a mocked upload job carries placeholder bytes, which must never reach the real backend",
    ),
    mocked(
        MockedSystem.LIBRARY, MockedSystem.UPLOAD_QUEUE, MockedSystem.UPLOAD_SESSION,
        why = "a mocked photo holds no bytes and no platform resource, so a real transfer has nothing to send",
    ),
    Rule(
        MockedSystem.EXTENSION_REGISTRY, whenMocked = false,
        then = listOf(
            MockedSystem.BACKEND, MockedSystem.LIBRARY, MockedSystem.UPLOAD_QUEUE, MockedSystem.FILES,
            MockedSystem.DATABASES, MockedSystem.PREFERENCES, MockedSystem.KEYCHAIN,
        ),
        thenMocked = false,
        why = "a real registration is invoked by the operating system in the extension's OWN process, and a mocked " +
            "system the extension writes would then be written by two processes; mock the registration too, and the " +
            "control channel invokes the extension inside the app (/os/photokit-ext/processRawValue)",
    ),
)

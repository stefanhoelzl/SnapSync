package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **A trigger flow never outlives its own run** (capability `architecture-guards`; law:
 * `module-architecture`). A flow coordinates the work an OS callback caused, and its caller is a shell
 * that reports completion back to the operating system. A flow that detaches work returns before that
 * work starts, so the shell's report is a false statement about work it never observed — and iOS is
 * entitled to suspend the process on the strength of it. Measured in SNAPSYNC-6: `← onSilentPush (18ms)`
 * against 41 s of real work, and a five-photo import batch frozen ~1 s into a ~30 s budget.
 *
 * Two doors, both closed here, because closing one leaves the other open:
 *
 * 1. a `CoroutineScope` parameter or field — the flow launches into it directly;
 * 2. a lambda parameter returning `Unit` that is not `suspend` — the flow cannot see which of those the
 *    composition backed with a detached launch, and a non-suspend `() -> Unit` can only ever be
 *    fire-and-forget. `SilentPush`'s `refreshAttestation: () -> Unit` was exactly this: the flow held no
 *    scope, the zone gate was green, and the work escaped anyway.
 *
 * Value-returning lambdas are deliberately untouched: a lambda that must produce a value has to produce
 * it synchronously, so a flow's reads (`() -> String?`, `() -> Boolean`) cannot detach anything.
 *
 * The transcriber is the third enforcement — `coroutineScope { launch { … } }` is in the closed flow
 * grammar and an escaping `scope.launch` is not, so a detaching flow also fails diagram generation
 * (capability `architecture-diagrams`).
 */
class ZoneFlowLifetimeTest {

    /** A constructor/function parameter declaration: `name: Type` up to the next `,` or `)`. */
    private val unitLambdaParam =
        Regex("""\bval\s+(\w+)\s*:\s*(\([^)]*\)\s*->\s*Unit)""")

    @Test
    fun `flows declare no CoroutineScope`() {
        val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, "flow")
        if (ZoneGates.pendingOrEmpty("flow-lifetime", ZoneGates.domainSrc, files)) return
        val violations = files!!.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> Regex("""\bCoroutineScope\b""").containsMatchIn(line) }
                .map { (i, line) ->
                    "${file.name}:${i + 1} declares a CoroutineScope (`${line.trim()}`) — " +
                        "a flow that can launch is a flow that can outlive its own run"
                }
        }
        assertTrue(
            violations.isEmpty(),
            "flow-lifetime gate violations (law: module-architecture " +
                "\"A trigger flow never outlives its own run\"):\n  " + violations.joinToString("\n  "),
        )
    }

    @Test
    fun `every Unit-returning lambda a flow accepts is suspend`() {
        val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, "flow")
        if (ZoneGates.pendingOrEmpty("flow-lifetime", ZoneGates.domainSrc, files)) return
        val violations = files!!.flatMap { file ->
            val text = file.readText()
            unitLambdaParam.findAll(text).mapNotNull { m ->
                // `suspend (…) -> Unit` is the compliant form; the regex above matches only the
                // non-suspend one because `suspend` would precede the `(` it anchors on.
                val decl = m.value
                val lineNumber = text.substring(0, m.range.first).count { it == '\n' } + 1
                "${file.name}:$lineNumber accepts `${m.groupValues[1]}: ${m.groupValues[2]}` — a " +
                    "Unit-returning lambda a flow awaits must be `suspend`, or the work behind it detaches"
            }.toList()
        }
        assertTrue(
            violations.isEmpty(),
            "flow-lifetime gate violations (law: module-architecture " +
                "\"A trigger flow never outlives its own run\"):\n  " + violations.joinToString("\n  "),
        )
    }
}

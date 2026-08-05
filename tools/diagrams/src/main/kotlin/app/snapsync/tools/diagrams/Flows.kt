package app.snapsync.tools.diagrams

/**
 * The flow transcriber (capability `architecture-diagrams`): one sequence diagram per flow in
 * `:domain`'s `flow/` zone, transcribed from the flow bodies themselves. The trigger inventory is
 * DERIVED — the `flow/` directory listing, one file per trigger — never hand-enumerated (spec
 * `module-architecture`, "Commands cross one door").
 *
 * **The generation failure is a hard gate** (armed at the migration finale): a construct outside
 * the closed grammar throws, which fails `:tools:diagrams:generate` (the CI `diagrams` job) AND the
 * in-process freshness test under `./gradlew build` — an untranscribable flow is a law violation,
 * not a rendering problem. The closed grammar (spec `architecture-diagrams`):
 *
 *  - straight-line calls — features, and the `compose/`-built effect lambdas ("effects" below);
 *  - an AWAITED fan-out `coroutineScope { launch { … } … }` (the concurrent form), whose branch
 *    bodies are themselves grammar-bound and may each open with one guard. An escaping
 *    `scope.launch` is NO LONGER legal in a flow: flows hold no `CoroutineScope` (law "A trigger
 *    flow never outlives its own run"), so the detaching form is not expressible here and the
 *    transcriber refuses it rather than rendering it;
 *  - a `when` over a feature-returned sealed result, each branch a single call / launch / `Unit`;
 *  - a single **leading** guard clause (`val x = codec(...)` + `if (x == null) { log; return }`,
 *    or a sole `<call>?.let { … }` guarded region);
 *  - a best-effort wrap (`runCatching { call }.onFailure { log-only }`) — the absorb is
 *    diagnostics, transparent to transcription;
 *  - a fan-out loop over an injected receiver list (`for (r in receivers) { best-effort r(…) }`);
 *  - `log.*` statements — diagnostics, omitted.
 */

/** The flow zone — the transcriber's derived scope. */
private const val FLOW_DIR = "domain/src/commonMain/kotlin/app/snapsync/flow"

/** rel-path (under `architecture/flows/`) → rendered markdown, one file per flow. */
fun flowsMarkdown(sources: List<KtSource>): Map<String, String> {
    val flows = sources.filter { it.relPath.startsWith("$FLOW_DIR/") && it.relPath.endsWith(".kt") }
    check(flows.isNotEmpty()) {
        "flow transcriber scanned nothing under $FLOW_DIR — the flow/ zone moved; a gate that " +
            "scans nothing must fail, not pass (capability architecture-guards)"
    }
    return flows.associate { src ->
        val name = src.relPath.substringAfterLast('/').removeSuffix(".kt")
        "architecture/flows/$name.md" to renderFlow(src, name)
    }
}

// ---- transcription model -------------------------------------------------------------------

private sealed interface Step
private data class Call(
    val receiver: String,
    val method: String,
    val hasArgs: Boolean,
    val launch: Boolean = false,
    val bestEffort: Boolean = false,
) : Step
private data class Guard(val desc: String) : Step
private data class Guarded(val desc: String, val steps: List<Step>) : Step
private data class Launch(val steps: List<Step>) : Step
/** `coroutineScope { … }` — its branches run concurrently and the flow AWAITS all of them. */
private data class Awaited(val steps: List<Step>) : Step
private data class Alt(val subject: String, val branches: List<Pair<String, List<Step>>>) : Step
private data class FanOut(val collection: String, val steps: List<Step>) : Step

private class GrammarViolation(message: String) : IllegalStateException(message)

private fun violation(src: KtSource, offset: Int, kind: String, snippet: String): Nothing =
    throw GrammarViolation(
        "flow transcriber: ${src.relPath}:${src.lineOf(offset)} — $kind outside the closed " +
            "grammar: `${snippet.take(120)}`. An untranscribable flow is a law violation (specs " +
            "`architecture-diagrams` / `module-architecture`): use a straight-line feature call, " +
            "an awaited coroutineScope fan-out, a `when` over a feature-returned sealed result, the single " +
            "leading guard clause, a best-effort wrap, or a receiver-list fan-out — or sink the " +
            "rule into a feature.",
    )

// ---- parsing helpers ------------------------------------------------------------------------

private fun skipWs(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}

/** From an opening delimiter at/after [from], return the index just past its balanced close. */
internal fun skipBalanced(text: String, from: Int, open: Char, close: Char): Int {
    var i = text.indexOf(open, from)
    if (i < 0) return text.length
    var depth = 0
    while (i < text.length) {
        when (text[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return text.length
}

private data class Body(val start: Int, val end: Int)

private data class FlowFunction(val name: String, val body: Body)

/** Every `fun <name>(…) { … }` in the file, in source order (flows have block bodies only). */
private fun functions(src: KtSource): List<FlowFunction> {
    val text = src.stripped
    val out = mutableListOf<FlowFunction>()
    for (m in Regex("""\bfun\s+(\w+)\s*\(""").findAll(text)) {
        var i = skipBalanced(text, m.range.last, '(', ')')
        i = skipWs(text, i)
        if (i < text.length && text[i] == ':') { // declared return type
            while (i < text.length && text[i] != '=' && text[i] != '{' && text[i] != '\n') i++
            i = skipWs(text, i)
        }
        if (i >= text.length || text[i] != '{') {
            violation(src, m.range.first, "non-block function body (`fun ${m.groupValues[1]}`)", "expression-bodied flow function")
        }
        out += FlowFunction(m.groupValues[1], Body(i + 1, skipBalanced(text, i, '{', '}') - 1))
    }
    return out
}

/** One raw statement: [text] runs to its end incl. any chained `.onFailure { … }` continuation. */
private data class Stmt(val start: Int, val end: Int, val text: String)

/** Split [body] into depth-0 statements; blocks and chained continuations stay attached. */
private fun statements(src: KtSource, body: Body): List<Stmt> {
    val text = src.stripped
    val out = mutableListOf<Stmt>()
    var i = body.start
    while (i < body.end) {
        while (i < body.end && text[i].isWhitespace()) i++
        if (i >= body.end) break
        val start = i
        var depth = 0
        var j = i
        while (j < body.end) {
            when (text[j]) {
                '(' -> depth++
                ')' -> depth--
                '{' -> if (depth == 0) {
                    j = skipBalanced(text, j, '{', '}')
                    // Chained continuations stay part of THIS statement: `else`/`catch`/`finally`
                    // keywords, or a chained call (`.onFailure { … }`).
                    while (true) {
                        val t = skipWs(text, j)
                        if (t < body.end && (
                                text.startsWith("else", t) || text.startsWith("catch", t) ||
                                    text.startsWith("finally", t) || text[t] == '.'
                                )
                        ) {
                            val nextBrace = text.indexOf('{', t)
                            if (nextBrace < 0 || nextBrace >= body.end) break
                            j = skipBalanced(text, nextBrace, '{', '}')
                        } else {
                            break
                        }
                    }
                    break
                }
                '\n' -> if (depth == 0) break
            }
            j++
        }
        val end = minOf(j, body.end)
        out += Stmt(start, end, text.substring(start, end).trim().replace(Regex("\\s+"), " "))
        i = end + 1
    }
    return out
}

// ---- the grammar ----------------------------------------------------------------------------

private val CALL = Regex("""^(?:return@\w+\s+)?([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)
private val LAUNCH = Regex("""^launch \{ (.*) \}$""", RegexOption.DOT_MATCHES_ALL)
private val AWAITED = Regex("""^coroutineScope \{ (.*) \}$""", RegexOption.DOT_MATCHES_ALL)
private val BEST_EFFORT = Regex("""^runCatching \{ (.*?) \}\s*\.onFailure \{ log\..*\}$""", RegexOption.DOT_MATCHES_ALL)
private val GUARDED = Regex("""^([A-Za-z_][\w.]*\([^)]*\))\?\.let \{ (?:([A-Za-z_]\w*) -> )?(.*) \}$""", RegexOption.DOT_MATCHES_ALL)
private val FAN_OUT = Regex("""^for \(([A-Za-z_]\w*) in ([A-Za-z_]\w*)\) \{ (.*) \}$""", RegexOption.DOT_MATCHES_ALL)
private val WHEN_SUBJECT = Regex("""^when \((?:val ([A-Za-z_]\w*) = )?(.*)\) \{(.*)\}$""", RegexOption.DOT_MATCHES_ALL)
private val NULL_GUARD_VAL = Regex("""^val ([A-Za-z_]\w*) = ([A-Za-z_][\w.]*)\((.*)\)$""")

private fun callStep(stmt: String, launch: Boolean = false, bestEffort: Boolean = false): Call? {
    val m = CALL.find(stmt) ?: return null
    if (stmt.startsWith("if") || stmt.startsWith("when") || stmt.startsWith("for") || stmt.startsWith("return ")) return null
    val chain = m.groupValues[1]
    return Call(
        receiver = chain.substringBeforeLast('.', missingDelimiterValue = ""),
        method = chain.substringAfterLast('.'),
        hasArgs = m.groupValues[2].isNotBlank(),
        launch = launch,
        bestEffort = bestEffort,
    )
}

/** Transcribe a body's statements. [guardSlot] permits the single leading guard forms. */
private fun transcribe(src: KtSource, body: Body, guardSlot: Boolean): List<Step> {
    val stmts = statements(src, body)
    val steps = mutableListOf<Step>()
    var index = 0
    while (index < stmts.size) {
        val stmt = stmts[index]
        val text = stmt.text
        val leading = steps.isEmpty() && guardSlot
        when {
            text.isEmpty() || text.startsWith("log.") -> Unit // diagnostics — omitted

            // Single leading guard, pair form: `val x = codec(...)` + `if (x == null) { log; return }`.
            leading && NULL_GUARD_VAL.matches(text) && index + 1 < stmts.size &&
                isNullReturnGuard(stmts[index + 1].text, NULL_GUARD_VAL.find(text)!!.groupValues[1]) -> {
                val m = NULL_GUARD_VAL.find(text)!!
                steps += Guard("only when ${m.groupValues[2]}(…) resolves ${m.groupValues[1]}")
                index++ // consume the `if` too
            }

            // Single leading guard, region form: sole `<call>?.let { … }` statement.
            leading && GUARDED.matches(text) -> {
                val m = GUARDED.find(text)!!
                if (index != stmts.size - 1) violation(src, stmt.start, "a guarded region that is not the body's sole statement", text)
                steps += Guarded("only when ${m.groupValues[1]} resolves", transcribeFragment(src, stmt, m.groupValues[3]))
            }

            AWAITED.matches(text) -> {
                // A `coroutineScope { … }` region is a BODY, not a fragment: it holds several
                // statements (one per concurrent branch), so it is re-split and transcribed like any
                // other body rather than parsed as a single expression.
                val open = src.stripped.indexOf('{', stmt.start)
                val close = skipBalanced(src.stripped, open, '{', '}') - 1
                steps += Awaited(transcribe(src, Body(open + 1, close), guardSlot = false))
            }

            LAUNCH.matches(text) -> {
                val inner = LAUNCH.find(text)!!.groupValues[1].trim()
                steps += Launch(transcribeFragment(src, stmt, inner, guardSlot = true))
            }

            BEST_EFFORT.matches(text) -> {
                val inner = BEST_EFFORT.find(text)!!.groupValues[1].trim()
                val call = callStep(inner, bestEffort = true)
                    ?: violation(src, stmt.start, "a best-effort wrap around a non-call", text)
                steps += call
            }

            FAN_OUT.matches(text) -> {
                val m = FAN_OUT.find(text)!!
                steps += FanOut(m.groupValues[2], transcribeFragment(src, stmt, m.groupValues[3].trim()))
            }

            WHEN_SUBJECT.matches(text) -> steps += transcribeWhen(src, stmt)

            else -> {
                val call = callStep(text) ?: violation(src, stmt.start, classify(text), text)
                steps += call
            }
        }
        index++
    }
    return steps
}

private fun isNullReturnGuard(stmt: String, name: String): Boolean {
    val m = Regex("""^if \($name == null\) \{ (.*) \}$""", RegexOption.DOT_MATCHES_ALL).find(stmt) ?: return false
    val inner = m.groupValues[1].trim()
    // The guard body may log (diagnostics) and must return — nothing else.
    return inner.split(Regex("(?<=[})])\\s+")).all { it.startsWith("log.") || it == "return" || it.endsWith(" return") }
        .and(inner.endsWith("return"))
}

/** Transcribe a re-parsed fragment (a lambda/loop body captured by a statement-level regex). */
private fun transcribeFragment(src: KtSource, stmt: Stmt, fragment: String, guardSlot: Boolean = false): List<Step> {
    val trimmed = fragment.trim()
    // A fragment is a mini-body: try the composed forms first, then a plain call.
    if (GUARDED.matches(trimmed)) {
        val m = GUARDED.find(trimmed)!!
        return listOf(Guarded("only when ${m.groupValues[1]} resolves", transcribeFragment(src, stmt, m.groupValues[3])))
    }
    if (BEST_EFFORT.matches(trimmed)) {
        val inner = BEST_EFFORT.find(trimmed)!!.groupValues[1].trim()
        val call = callStep(inner, bestEffort = true) ?: violation(src, stmt.start, "a best-effort wrap around a non-call", trimmed)
        return listOf(call)
    }
    val call = callStep(trimmed) ?: violation(src, stmt.start, classify(trimmed), trimmed)
    return listOf(call)
}

private fun transcribeWhen(src: KtSource, stmt: Stmt): Alt {
    val m = WHEN_SUBJECT.find(stmt.text)!!
    val subject = m.groupValues[2].trim()
    if (CALL.find(subject) == null) violation(src, stmt.start, "a `when` over a non-call subject (the sealed result must come from a feature call)", stmt.text)
    val branchesText = m.groupValues[3].trim()
    val branches = mutableListOf<Pair<String, List<Step>>>()
    // Branches: `is X -> …` / `X -> …` / `else -> …`, each a single call / launch / `Unit`.
    val branchRegex = Regex("""(is [A-Za-z_][\w.]*|[A-Za-z_][\w.]*|else) -> """)
    val matches = branchRegex.findAll(branchesText).toList()
    if (matches.isEmpty()) violation(src, stmt.start, "a `when` with no recognizable branches", stmt.text)
    for ((bi, bm) in matches.withIndex()) {
        val label = bm.groupValues[1]
        val end = if (bi + 1 < matches.size) matches[bi + 1].range.first else branchesText.length
        val branchBody = branchesText.substring(bm.range.last + 1, end).trim()
        val steps = when {
            branchBody == "Unit" -> emptyList()
            LAUNCH.matches(branchBody) ->
                listOf(Launch(transcribeFragment(src, stmt, LAUNCH.find(branchBody)!!.groupValues[1].trim(), guardSlot = true)))
            else -> listOf(
                callStep(branchBody) ?: violation(src, stmt.start, "a `when` branch that is not a single call/launch/Unit", branchBody),
            )
        }
        branches += label to steps
    }
    return Alt(subject, branches)
}

private fun classify(stmt: String): String = when {
    stmt.startsWith("if ") || stmt.startsWith("if(") -> "an `if` conditional"
    stmt.contains("?.let") -> "a non-leading `?.let` guard"
    stmt.startsWith("val ") || stmt.startsWith("var ") -> "an assignment"
    stmt.startsWith("return") -> "an early return"
    else -> "a construct"
}

// ---- rendering ------------------------------------------------------------------------------

private fun renderFlow(src: KtSource, name: String): String {
    val sb = StringBuilder()
    sb.append("# Flow — `").append(name).append("`\n")
    sb.append("\n")
    sb.append("Generated by `./gradlew architectureDiagrams` from `").append(src.relPath).append("`.\n")
    sb.append("Do not edit — the `:tools:diagrams` freshness test fails on drift; regenerate instead.\n")
    sb.append("\n")
    sb.append("Transcribed against the closed flow grammar; a construct outside it FAILS generation\n")
    sb.append("(the hard gate, armed at the migration finale — an untranscribable flow is a law\n")
    sb.append("violation, spec `architecture-diagrams`). Bare calls target the flow's injected\n")
    sb.append("`compose/`-built effect lambdas, rendered as `effects`; `log.*` lines are diagnostics\n")
    sb.append("and omitted. Async arrows are concurrent branches, awaited by the enclosing flow.\n")
    val helperNames = functions(src).map { it.name }.toSet()
    for (fn in functions(src)) {
        val steps = transcribe(src, fn.body, guardSlot = true)
        sb.append("\n")
        sb.append("## `").append(fn.name).append(if (fn.name == "run") "` — the flow command\n" else "` — helper\n")
        sb.append("\n")
        sb.append("```mermaid\n")
        sb.append("sequenceDiagram\n")
        sb.append("  participant Trigger\n")
        sb.append("  participant ").append(name).append("\n")
        val declared = linkedSetOf("Trigger", name)
        declareParticipants(steps, name, helperNames, declared, sb)
        sb.append("  Trigger->>").append(name).append(": ").append(fn.name).append("(…)\n")
        renderSteps(steps, name, helperNames, sb, indent = "  ", async = false)
        sb.append("```\n")
    }
    return sb.toString()
}

private fun participantId(receiver: String, flow: String): String = when {
    receiver.isEmpty() -> "effects"
    else -> receiver.replace('.', '_')
}

private fun collectCalls(steps: List<Step>): List<Call> = steps.flatMap {
    when (it) {
        is Call -> listOf(it)
        is Launch -> collectCalls(it.steps)
        is Awaited -> collectCalls(it.steps)
        is Guarded -> collectCalls(it.steps)
        is Alt -> it.branches.flatMap { (_, s) -> collectCalls(s) }
        is FanOut -> collectCalls(it.steps)
        is Guard -> emptyList()
    }
}

private fun declareParticipants(
    steps: List<Step>,
    flow: String,
    helpers: Set<String>,
    declared: MutableSet<String>,
    sb: StringBuilder,
) {
    for (call in collectCalls(steps)) {
        val display = call.receiver.ifEmpty { if (call.method in helpers) flow else "effects" }
        if (declared.add(display)) {
            val id = participantId(call.receiver, flow)
            if (id == display) sb.append("  participant ").append(display).append("\n")
            else sb.append("  participant ").append(id).append(" as ").append(display).append("\n")
        }
    }
}

private fun renderSteps(
    steps: List<Step>,
    flow: String,
    helpers: Set<String>,
    sb: StringBuilder,
    indent: String,
    async: Boolean,
) {
    for (step in steps) {
        when (step) {
            is Call -> {
                val arrow = if (async || step.launch) "--)" else "->>"
                val target = if (step.receiver.isEmpty() && step.method in helpers) flow
                else participantId(step.receiver, flow)
                sb.append(indent).append(flow).append(arrow).append(target).append(": ")
                    .append(step.method).append(if (step.hasArgs) "(…)" else "()")
                if (step.bestEffort) sb.append(" [best-effort]")
                sb.append("\n")
            }
            is Guard -> sb.append(indent).append("Note over ").append(flow).append(": guard — ").append(step.desc).append("\n")
            is Guarded -> {
                sb.append(indent).append("opt ").append(step.desc).append("\n")
                renderSteps(step.steps, flow, helpers, sb, "$indent  ", async)
                sb.append(indent).append("end\n")
            }
            is Launch -> renderSteps(step.steps, flow, helpers, sb, indent, async = true)
            is Awaited -> {
                sb.append(indent).append("par concurrent — awaited before the flow returns\n")
                renderSteps(step.steps, flow, helpers, sb, "$indent  ", async)
                sb.append(indent).append("end\n")
            }
            is Alt -> {
                for ((i, branch) in step.branches.withIndex()) {
                    val (label, branchSteps) = branch
                    sb.append(indent).append(if (i == 0) "alt " else "else ").append(step.subject).append(" = ").append(label).append("\n")
                    if (branchSteps.isEmpty()) {
                        sb.append(indent).append("  Note over ").append(flow).append(": nothing\n")
                    } else {
                        renderSteps(branchSteps, flow, helpers, sb, "$indent  ", async)
                    }
                }
                sb.append(indent).append("end\n")
            }
            is FanOut -> {
                sb.append(indent).append("loop each of ").append(step.collection).append("\n")
                renderSteps(step.steps, flow, helpers, sb, "$indent  ", async)
                sb.append(indent).append("end\n")
            }
        }
    }
}

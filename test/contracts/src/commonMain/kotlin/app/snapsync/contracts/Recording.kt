package app.snapsync.contracts

/**
 * What an adapter asked the operating system during one run of a contract on one host, and what it was
 * answered (`docs/architecture.md`, "A recording is one committed plain-text file per contract and
 * host"). Input to a clause on replay — never an expectation.
 *
 * Text form, at `test/contracts/recordings/<Contract>@<HOST>.rec`, or `<Contract>@<HOST>.<GRANT>.rec` for a
 * binding that declares the photo grant it ran under ([recordingName]):
 *
 * ```
 * # contract: SecureStore
 * # host: IOS_DEVICE_APP
 * # grant: GRANTED        (only where the binding declares one)
 * # device: iPhone SE (2nd generation)
 * ...
 * [CLAUSE_ID]
 * add(acct=a pdmn=ck svce=s v_Data=x) -> 0
 * ```
 *
 * Header lines are `# key: value` in insertion order; blocks are sorted by clause id; each line is
 * `call -> answer`. Calls and answers are opaque strings rendered by the adapter's seam — deterministic,
 * with volatile keys already masked ([maskKeys]) — so this layer never learns a platform's vocabulary.
 */
class Recording(val header: List<Pair<String, String>>, val blocks: Map<String, List<Exchange>>) {

    val host: String? get() = header.firstOrNull { it.first == "host" }?.second

    /** The photo grant the run held, where its binding declared one. */
    val grant: String? get() = header.firstOrNull { it.first == "grant" }?.second

    fun render(): String = buildString {
        header.forEach { (k, v) -> append("# ").append(k).append(": ").append(v).append('\n') }
        blocks.keys.sorted().forEach { id ->
            append('[').append(id).append("]\n")
            blocks.getValue(id).forEach { append(it.call).append(ARROW).append(it.answer).append('\n') }
        }
    }

    companion object {
        const val ARROW = " -> "

        /** Parses [render]'s output. Malformed input throws, naming the line — a recording never half-loads. */
        fun parse(text: String): Recording {
            val header = mutableListOf<Pair<String, String>>()
            val blocks = linkedMapOf<String, MutableList<Exchange>>()
            var current: MutableList<Exchange>? = null
            text.lineSequence().forEachIndexed { i, line ->
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("# ") && current == null -> {
                        val sep = line.indexOf(": ")
                        require(sep > 2) { "recording line ${i + 1}: header without ': ' — $line" }
                        header += line.substring(2, sep) to line.substring(sep + 2)
                    }
                    line.startsWith("[") && line.endsWith("]") -> {
                        val id = line.substring(1, line.length - 1)
                        require(id !in blocks) { "recording line ${i + 1}: duplicate block [$id]" }
                        current = mutableListOf<Exchange>().also { blocks[id] = it }
                    }
                    else -> {
                        val block = requireNotNull(current) { "recording line ${i + 1}: exchange outside a block — $line" }
                        val at = line.lastIndexOf(ARROW)
                        require(at > 0) { "recording line ${i + 1}: exchange without '$ARROW' — $line" }
                        block += Exchange(line.substring(0, at), line.substring(at + ARROW.length))
                    }
                }
            }
            return Recording(header, blocks)
        }
    }
}

class Exchange(val call: String, val answer: String) {
    override fun equals(other: Any?) = other is Exchange && other.call == call && other.answer == answer
    override fun hashCode() = call.hashCode() * 31 + answer.hashCode()
    override fun toString() = "$call${Recording.ARROW}$answer"
}

/**
 * Collects exchanges into per-clause blocks during a device run. The seam calls [record] for every
 * operating-system call; the binding calls [open] when a clause begins, so seeding calls made while
 * entering the clause's state land in that clause's block too.
 */
class Recorder(from: Recording? = null) {
    private val blocks = linkedMapOf<String, MutableList<Exchange>>().apply {
        from?.blocks?.forEach { (id, exchanges) -> put(id, exchanges.toMutableList()) }
    }
    private var current: MutableList<Exchange>? = null

    fun open(clauseId: String) {
        current = mutableListOf<Exchange>().also { blocks[clauseId] = it }
    }

    /**
     * Continues [clauseId]'s block where it stopped — a state entered across operating-system calls, whose earlier
     * calls a previous process recorded ([Recorder] constructed from what it kept).
     */
    fun resume(clauseId: String) {
        current = blocks.getOrPut(clauseId) { mutableListOf() }
    }

    fun record(call: String, answer: String) {
        checkNotNull(current) { "an operating-system call was made outside any clause: $call" }.add(Exchange(call, answer))
    }

    fun recording(header: List<Pair<String, String>>): Recording = Recording(header, blocks)
}

/**
 * Answers one clause's calls from its recorded block, EXACTLY and IN ORDER (`docs/architecture.md`,
 * "Replay matches exactly, in order, over deterministic clauses"). Any other call — a different request,
 * a reordering, or a call past the end — is a [Divergence]: the recording must be retaken.
 */
class Replayer(private val clauseId: String, private val exchanges: List<Exchange>) {
    private var next = 0

    fun answer(call: String): String {
        val expected = exchanges.getOrNull(next)
            ?: throw Divergence("[$clauseId] call #${next + 1} was not recorded (the recording ends at ${exchanges.size}): $call")
        if (expected.call != call) {
            throw Divergence("[$clauseId] call #${next + 1} differs from the recording\n  recorded: ${expected.call}\n  made:     $call")
        }
        next++
        return expected.answer
    }

    /** Every recorded call was made. Called when the clause's subject is disposed. */
    fun assertExhausted() {
        if (next < exchanges.size) {
            throw Divergence("[$clauseId] ${exchanges.size - next} recorded call(s) were never made, first: ${exchanges[next].call}")
        }
    }
}

/**
 * Replaces the value of every `key=value` token whose key is in [volatile] with a fixed placeholder, so a
 * re-recording diffs only when the operating system's substantive answer moved. Tokens are
 * space-separated; a value is everything up to the next space.
 */
fun maskKeys(rendered: String, volatile: Set<String>): String =
    rendered.split(' ').joinToString(" ") { token ->
        val eq = token.indexOf('=')
        if (eq > 0 && token.substring(0, eq) in volatile) "${token.substring(0, eq)}=<masked>" else token
    }

@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What an adapter asked the operating system during one run of a contract on one host, what it was answered, and
 * what the operating system delivered into it unasked (`docs/architecture.md`, "A recording is one committed
 * plain-text file per contract and host"). Input to a clause on replay — never an expectation.
 *
 * Text form, at `test/contracts/recordings/<Contract>@<HOST>.rec`, or `<Contract>@<HOST>.<GRANT>.rec` for a
 * binding that declares the photo grant it ran under — `<Contract>@<HOST>.<PRECONDITION>.rec` for one that declares
 * another condition of the device ([recordingName]):
 *
 * ```
 * # contract: SecureStore
 * # host: IOS_DEVICE_APP
 * # grant: GRANTED        (only where the binding declares one; `precondition:` likewise)
 * # device: iPhone SE (2nd generation)
 * ...
 * [CLAUSE_ID]
 * add(acct=a pdmn=ck svce=s v_Data=x) -> 0
 * begin(name=n) -> 7
 * <- expired(name=n)
 * ```
 *
 * Header lines are `# key: value` in insertion order; blocks are sorted by clause id; each line is
 * `call -> answer`, or `<- event` for what the operating system delivered into the adapter of its own accord — an
 * expiration handler fired, a relaunch's session events, a report handed over — at the point it arrived. Calls,
 * answers and events are opaque strings rendered by the adapter's seam — deterministic, with volatile keys already
 * masked ([maskKeys]) — so this layer never learns a platform's vocabulary.
 */
class Recording(val header: List<Pair<String, String>>, val blocks: Map<String, List<Entry>>) {

    val host: String? get() = header.firstOrNull { it.first == "host" }?.second

    /** The photo grant the run held, where its binding declared one. */
    val grant: String? get() = header.firstOrNull { it.first == "grant" }?.second

    /** The condition of the device the run was taken under, where its binding declared one. */
    val precondition: String? get() = header.firstOrNull { it.first == "precondition" }?.second

    fun render(): String = buildString {
        header.forEach { (k, v) -> append("# ").append(k).append(": ").append(v).append('\n') }
        blocks.keys.sorted().forEach { id ->
            append('[').append(id).append("]\n")
            blocks.getValue(id).forEach { append(it).append('\n') }
        }
    }

    companion object {
        const val ARROW = " -> "

        /** What opens an event line: the operating system speaking into the adapter. */
        const val EVENT = "<- "

        /** Parses [render]'s output. Malformed input throws, naming the line — a recording never half-loads. */
        fun parse(text: String): Recording {
            val header = mutableListOf<Pair<String, String>>()
            val blocks = linkedMapOf<String, MutableList<Entry>>()
            var current: MutableList<Entry>? = null
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
                        current = mutableListOf<Entry>().also { blocks[id] = it }
                    }
                    else -> {
                        val block = requireNotNull(
                            current,
                        ) { "recording line ${i + 1}: exchange outside a block — $line" }
                        if (line.startsWith(EVENT)) {
                            block += Event(line.substring(EVENT.length))
                            return@forEachIndexed
                        }
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

/** One line of a clause's block. */
sealed interface Entry

/** The adapter called the operating system with [call] and was answered [answer]. */
class Exchange(val call: String, val answer: String) : Entry {
    override fun equals(other: Any?) = other is Exchange && other.call == call && other.answer == answer
    override fun hashCode() = call.hashCode() * 31 + answer.hashCode()
    override fun toString() = "$call${Recording.ARROW}$answer"
}

/**
 * The operating system delivered [text] into the adapter unasked — a callback, not an answer. Recorded where it
 * arrived, so a replay delivers it at the same point among the calls.
 */
class Event(val text: String) : Entry {
    override fun equals(other: Any?) = other is Event && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = "${Recording.EVENT}$text"
}

/**
 * Collects exchanges into per-clause blocks during a device run. The seam calls [record] for every
 * operating-system call and [event] for every callback the operating system makes into it; the binding calls [open]
 * when a clause begins, so seeding calls made while entering the clause's state land in that clause's block too.
 */
class Recorder(from: Recording? = null) {
    private val blocks = linkedMapOf<String, MutableList<Entry>>().apply {
        from?.blocks?.forEach { (id, entries) -> put(id, entries.toMutableList()) }
    }
    private var current: MutableList<Entry>? = null
    private val guard = Guard()

    fun open(clauseId: String) {
        current = mutableListOf<Entry>().also { blocks[clauseId] = it }
    }

    /**
     * Continues [clauseId]'s block where it stopped — a state entered across operating-system calls, whose earlier
     * calls a previous process recorded ([Recorder] constructed from what it kept).
     */
    fun resume(clauseId: String) {
        current = blocks.getOrPut(clauseId) { mutableListOf() }
    }

    // A callback can arrive on any thread while the clause's own calls are recorded on another.
    fun record(call: String, answer: String) = guard.locked {
        checkNotNull(
            current,
        ) { "an operating-system call was made outside any clause: $call" }.add(Exchange(call, answer))
    }

    /** The operating system delivered [text] into the adapter, now. */
    fun event(text: String) = guard.locked {
        checkNotNull(current) { "the operating system delivered an event outside any clause: $text" }.add(Event(text))
    }

    fun recording(header: List<Pair<String, String>>): Recording = Recording(header, blocks)
}

/**
 * Answers one clause's calls from its recorded block, EXACTLY and IN ORDER (`docs/architecture.md`,
 * "Replay matches exactly, in order, over deterministic clauses"). Any other call — a different request,
 * a reordering, or a call past the end — is a [Divergence]: the recording must be retaken.
 *
 * The block's events are the seam's to deliver: it takes those due at the current point with [takeEvents] — after a
 * call it answered, or before any — and hands each to the adapter as the operating system did. A call made while an
 * event is still due diverges, and so does an event never taken: the adapter is replayed against exactly what
 * happened, in the order it happened.
 */
class Replayer(private val clauseId: String, private val entries: List<Entry>) {
    private var next = 0
    private val guard = Guard()

    fun answer(call: String): String = guard.locked {
        val expected = entries.getOrNull(next)
            ?: throw Divergence("[$clauseId] call #${next + 1} was not recorded (the recording ends at ${entries.size}): $call")
        val exchange = expected as? Exchange ?: throw Divergence(
            "[$clauseId] entry #${next + 1} is an event the replay never delivered\n  recorded: $expected\n  made:     $call",
        )
        if (exchange.call != call) {
            throw Divergence(
                "[$clauseId] call #${next + 1} differs from the recording\n  recorded: ${exchange.call}\n  made:     $call",
            )
        }
        next++
        exchange.answer
    }

    /** The events recorded at this point — consecutive, up to the next call — each now counted delivered. */
    fun takeEvents(): List<String> = guard.locked {
        buildList {
            while (true) {
                val event = entries.getOrNull(next) as? Event ?: break
                add(event.text)
                next++
            }
        }
    }

    /** Every recorded call was made and every event delivered. Called when the clause's subject is disposed. */
    fun assertExhausted() = guard.locked {
        if (next < entries.size) {
            throw Divergence(
                "[$clauseId] ${entries.size - next} recorded entr(y/ies) were never made or delivered, first: ${entries[next]}",
            )
        }
    }
}

/**
 * Mutual exclusion for a recorder or replayer: an operating-system callback arrives on its own thread while the
 * clause makes its calls on another. Held for a list append, so a spin is all it needs.
 */
private class Guard {
    private val held = AtomicBoolean(false)

    fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            // Another thread holds it for one append.
        }
        try {
            return block()
        } finally {
            held.store(false)
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

/** Where a device recording is taken, completing "record it … over the rig". */
const val RECORDED_ON_A_DEVICE: String = "on a device"

/**
 * A replay binding's entry into one clause: [clauseId]'s block of the recording [name] — its text looked up in
 * [recordings], the module's generated map — handed to [ready] as a [Replayer]. With no such recording, or no block for
 * the clause in it, the clause is [Entered.Unreachable], naming what to record ([recordedWhere]) or re-record.
 */
inline fun <T> replayerFor(
    recordings: Map<String, String>,
    name: String,
    clauseId: String,
    recordedWhere: String = RECORDED_ON_A_DEVICE,
    ready: (Replayer) -> Entered<T>,
): Entered<T> {
    val tape = recordings[name]?.let(Recording::parse)
        ?: return Entered.Unreachable("no recording $name.rec — record it $recordedWhere over the rig")
    val block = tape.blocks[clauseId]
        ?: return Entered.Unreachable("$name.rec holds no block for $clauseId — re-record")
    return ready(Replayer(clauseId, block))
}

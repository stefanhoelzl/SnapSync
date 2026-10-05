package app.snapsync.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How one fixture route answers a transfer — chosen by the clause, not by the system under contract (capability
 * `docs/architecture.md`, "An adapter bound per compilation target is real for the clauses it runs there": a server a
 * transport's clauses exchange bytes with is a clause input).
 */
sealed interface FixtureAnswer {
    /**
     * Answer [status]. A `GET` gets a body of [length] bytes ([TransferFixture.body]), declaring that length in
     * `Content-Length` unless [declaresLength] is false; a `PUT`'s body is kept for [FixtureObjects.landed].
     */
    data class Respond(
        val status: Int,
        val length: Int = 0,
        val declaresLength: Boolean = true,
        /** Declare [length] in `Content-Length` but send only half of it, then close — a transfer cut short. */
        val short: Boolean = false,
    ) : FixtureAnswer

    /** Never answer: the transfer stays open until it is cancelled. */
    data object Hold : FixtureAnswer

    /**
     * Answer [status] (a `3xx`) with a `Location` naming the same route with [to] as its last segment — the shape of an
     * edge route that redirects to a freshly presigned object URL. The `Location` is absolute, as a real redirect's is.
     */
    data class Redirect(val to: Respond, val status: Int = 302) : FixtureAnswer
}

/**
 * The route vocabulary both transfer hosts answer from — the loopback server `scripts/transfer-fixture.py` serves to
 * the simulator app, and the network a world binding plays. The answer is encoded in the route's LAST segment, so
 * neither side needs to be told anything but the URL:
 *
 * ```
 * /<Contract>/<CLAUSE_ID>/<name>/s200-n1024-len     200, 1024 bytes, Content-Length declared
 * /<Contract>/<CLAUSE_ID>/<name>/s404-n0-nolen      404, empty, no Content-Length
 * /<Contract>/<CLAUSE_ID>/<name>/s200-n64-short     200, declares 64 bytes, sends 32, closes
 * /<Contract>/<CLAUSE_ID>/<name>/hold               never answers
 * /<Contract>/<CLAUSE_ID>/<name>/r302-s200-n64-len  302, Location: the same route ending in s200-n64-len
 * ```
 *
 * ⚠️ `scripts/transfer-fixture.py` parses the same grammar; change both or neither.
 *
 * Every route is derived from the contract name and clause id, so clauses sharing one server never read each
 * other's objects, and a replay or a re-run sees the same addresses.
 */
object TransferFixture {

    /** The route [answer]s at, for the transfer [name] within [clauseId] of [contract]. */
    fun path(contract: String, clauseId: String, name: String, answer: FixtureAnswer): String =
        "/$contract/$clauseId/$name/" + segment(answer)

    private fun segment(answer: FixtureAnswer): String = when (answer) {
        FixtureAnswer.Hold -> HOLD
        is FixtureAnswer.Redirect -> "r${answer.status}-" + segment(answer.to)
        is FixtureAnswer.Respond ->
            "s${answer.status}-n${answer.length}-" + when {
                answer.short -> "short"
                answer.declaresLength -> "len"
                else -> "nolen"
            }
    }

    /** What [path] answers, or `null` if its last segment is not in the grammar. */
    fun answerOf(path: String): FixtureAnswer? {
        val segment = path.substringBefore('?').substringAfterLast('/')
        if (segment == HOLD) return FixtureAnswer.Hold
        REDIRECT.matchEntire(segment)?.let { r ->
            val to = respondOf(r.groupValues[2]) ?: return null
            return FixtureAnswer.Redirect(to, status = r.groupValues[1].toInt())
        }
        return respondOf(segment)
    }

    /** The route [redirect] at [path] names in its `Location`: the same route, its last segment the target's. */
    fun redirectTarget(path: String, redirect: FixtureAnswer.Redirect): String =
        path.substringBefore('?').substringBeforeLast('/') + "/" + segment(redirect.to)

    private fun respondOf(segment: String): FixtureAnswer.Respond? {
        val m = SEGMENT.matchEntire(segment) ?: return null
        return FixtureAnswer.Respond(
            status = m.groupValues[1].toInt(),
            length = m.groupValues[2].toInt(),
            declaresLength = m.groupValues[3] != "nolen",
            short = m.groupValues[3] == "short",
        )
    }

    /** The body a `GET` route of [length] answers with: a fixed byte pattern, so staged bytes can be compared. */
    fun body(length: Int): ByteArray = ByteArray(length) { (it % 251).toByte() }

    private const val HOLD = "hold"
    private val SEGMENT = Regex("""s(\d{3})-n(\d+)-(len|nolen|short)""")
    private val REDIRECT = Regex("""r(3\d{2})-(.+)""")
}

/** What a fixture received at one route: an object a `PUT` landed. Presence and type — the state reached. */
data class Landed(val contentType: String?)

/** A clause's read of what landed on the far side of its transfers — an observation handle over the fixture. */
fun interface FixtureObjects {
    /** What a `PUT` to [path] landed, or `null` when nothing has. */
    suspend fun landed(path: String): Landed?
}

/**
 * Polls [condition] in REAL time until it holds, and ends the clause as [Outcome.NotWithin] when [within] expires
 * (`docs/architecture.md`: a bounded wait on an operating-system callback that expires is `NotWithin`, never
 * `Failed` and never `Passed`). A clause body runs under `runTest`, whose `delay` is virtual — so the wait is moved
 * onto a real dispatcher, where a transfer can actually complete.
 */
internal suspend fun awaitWithin(within: Duration = TRANSFER_BOUND, condition: suspend () -> Boolean) {
    val held = withContext(Dispatchers.Default) {
        withTimeoutOrNull(within) {
            while (!condition()) delay(TRANSFER_POLL)
            true
        }
    }
    if (held == null) throw WaitExpired(within.inWholeMilliseconds)
}

/** A real-time pause, for asserting that something did NOT happen after a thing that did. */
internal suspend fun transferSettle() = withContext(Dispatchers.Default) { delay(TRANSFER_SETTLE) }

/**
 * A longer real-time pause, for asserting that a transfer HELD to unrestricted networks did not run on a restricted one
 * (capability `mobile-data`): every unheld transfer over loopback ends well inside it, so a held one still open after it
 * is held, not slow.
 */
internal suspend fun heldSettle() = withContext(Dispatchers.Default) { delay(HELD_SETTLE) }

/** Generous for a few kilobytes over loopback on a shared CI runner; expiry reads `NotWithin`, never `Passed`. */
internal val TRANSFER_BOUND: Duration = 20.seconds
private val TRANSFER_POLL = 25.milliseconds
private val TRANSFER_SETTLE = 750.milliseconds
private val HELD_SETTLE = 5.seconds

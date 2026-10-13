package app.snapsync.control.behaviour

import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What a `then` expects of the events since its act (`docs/testing.md`, "How a test waits"). Typed matchers, never
 * equality — an event carries values a test does not care about — and unordered unless the order is the contract
 * ([inOrder]).
 *
 * - A positive expectation ([screen], [event]) holds on its first match, and **fails** if the app settles first.
 * - A negative one ([never], [neverScreen]) holds only once the app has settled without a match.
 * - [times] counts matches up to the settle; [settled] judges the settle itself — its next timer, say.
 */
class Expectations internal constructor() {
    internal val all = mutableListOf<Expectation>()

    /** The app shows a screen whose layer is an [L] satisfying [that] — the first such layer, once the `then` holds. */
    inline fun <reified L : Layer> screen(noinline that: (L) -> Boolean = { true }): Seen<L> = Seen<L>().also { seen ->
        expect(Expectation.Positive("screen<${L::class.simpleName}>", screenMatch(that)) { seen.take((it as WorldEvent.UiChanged).ui.layer as L) })
    }

    /** An event of type [E] satisfying [that] happens — the first such event, once the `then` holds. */
    inline fun <reified E : WorldEvent> event(noinline that: (E) -> Boolean = { true }): Seen<E> = Seen<E>().also { seen ->
        expect(Expectation.Positive("event<${E::class.simpleName}>", eventMatch(that)) { seen.take(it as E) })
    }

    /** No event of type [E] satisfying [that] happens before the app settles. */
    inline fun <reified E : WorldEvent> never(noinline that: (E) -> Boolean = { true }) =
        expect(Expectation.Never("never<${E::class.simpleName}>", eventMatch(that)))

    /** No screen whose layer is an [L] satisfying [that] is shown before the app settles. */
    inline fun <reified L : Layer> neverScreen(noinline that: (L) -> Boolean = { true }) =
        expect(Expectation.Never("neverScreen<${L::class.simpleName}>", screenMatch(that)))

    /** The positive expectations in [block] hold in this order — only where the order is the contract. */
    fun inOrder(block: Expectations.() -> Unit) {
        val steps = Expectations().apply(block).all
        require(steps.all { it is Expectation.Positive }) { "inOrder takes positive expectations only" }
        all += Expectation.InOrder(steps.map { it as Expectation.Positive })
    }

    /** The one positive expectation in [block] matches exactly [n] times before the app settles — where a count is the behaviour. */
    fun times(n: Int, block: Expectations.() -> Unit) {
        val step = Expectations().apply(block).all.singleOrNull() as? Expectation.Positive
            ?: throw IllegalArgumentException("times takes exactly one positive expectation")
        all += Expectation.Times(n, step)
    }

    /** The settle that ends this `then` satisfies [that] — what its next timer is, say. */
    fun settled(that: (WorldEvent.Settled) -> Boolean) {
        all += Expectation.SettledWith(that)
    }

    @PublishedApi internal fun expect(expectation: Expectation) {
        all += expectation
    }

    @PublishedApi internal inline fun <reified L : Layer> screenMatch(noinline that: (L) -> Boolean): (WorldEvent) -> Boolean =
        { event -> event is WorldEvent.UiChanged && (event.ui.layer as? L)?.let(that) == true }

    @PublishedApi internal inline fun <reified E : WorldEvent> eventMatch(noinline that: (E) -> Boolean): (WorldEvent) -> Boolean =
        { event -> event is E && that(event) }
}

/** What a positive expectation matched, once its `then` has held. */
class Seen<T> @PublishedApi internal constructor() {
    private var matched: T? = null

    /** The match; read only after the `then` that declared it. */
    val value: T get() = checkNotNull(matched) { "read before its `then` held" }

    @PublishedApi internal fun take(value: T) {
        matched = value
    }
}

/** How long after the settle the app's next timer is due — `null` when none is pending. */
val WorldEvent.Settled.timerIn: Duration? get() = nextTimer?.let { Instant.parse(it) - Instant.parse(now) }

/** One expectation of a `then`. */
sealed interface Expectation {
    val label: String

    class Positive(
        override val label: String,
        val matches: (WorldEvent) -> Boolean,
        /** Told the event it matched, once its `then` has held. */
        val keep: (WorldEvent) -> Unit = {},
    ) : Expectation

    class Never(override val label: String, val matches: (WorldEvent) -> Boolean) : Expectation

    class InOrder(val steps: List<Positive>) : Expectation {
        override val label = steps.joinToString(prefix = "inOrder(", postfix = ")") { it.label }
    }

    class Times(val n: Int, val step: Positive) : Expectation {
        override val label = "times($n, ${step.label})"
    }

    class SettledWith(val that: (WorldEvent.Settled) -> Boolean) : Expectation {
        override val label = "settled { … }"
    }
}

internal enum class Verdict { Pending, Passed, Failed }

/**
 * Judges a `then`'s expectations against the events since its act began — read up to the first settle after its last
 * request, which ends it: what has not happened by then will not happen without the world moving.
 */
internal class Judge(
    private val expectations: List<Expectation>,
    /** The first event a settle may end the reading at: one before it fell between the act's own requests. */
    private val settlesFrom: Int = 0,
) {

    fun verdict(events: List<WorldEvent>): Verdict = read(events).verdict

    /** Hands each positive expectation the event it matched — once the `then` has held. */
    fun keep(events: List<WorldEvent>) = read(events).keep(events)

    /** Every expectation against the events actually seen. */
    fun report(events: List<WorldEvent>): String {
        val reading = read(events)
        return buildString {
            expectations.forEach { appendLine("  ${reading.outcome(it)}") }
            appendLine("events since the act:")
            append(events.render())
        }
    }

    private fun read(events: List<WorldEvent>): Reading {
        val reading = Reading(expectations)
        for ((i, event) in events.withIndex()) {
            if (event is WorldEvent.Settled && i >= settlesFrom) {
                reading.settle(i, event)
                break
            }
            reading.see(i, event)
        }
        return reading
    }

    private class Reading(private val expectations: List<Expectation>) {
        private val matchedAt = mutableMapOf<Expectation, Int>()
        private val brokenBy = mutableMapOf<Expectation, Int>()
        private val progress = mutableMapOf<Expectation.InOrder, Int>()
        private val counts = mutableMapOf<Expectation.Times, Int>()
        private var violation: Int? = null
        private var settledAt: Int? = null
        private var settled: WorldEvent.Settled? = null

        fun see(i: Int, event: WorldEvent) {
            if (event is WorldEvent.LaneViolation && violation == null) violation = i
            expectations.forEach { expectation ->
                when (expectation) {
                    is Expectation.Positive -> if (expectation !in matchedAt && expectation.matches(event)) matchedAt[expectation] = i
                    is Expectation.Never -> if (expectation !in brokenBy && expectation.matches(event)) brokenBy[expectation] = i
                    is Expectation.InOrder -> {
                        val at = progress[expectation] ?: 0
                        if (at < expectation.steps.size && expectation.steps[at].matches(event)) {
                            progress[expectation] = at + 1
                            if (at + 1 == expectation.steps.size) matchedAt[expectation] = i
                        }
                    }
                    is Expectation.Times -> if (expectation.step.matches(event)) counts[expectation] = (counts[expectation] ?: 0) + 1
                    is Expectation.SettledWith -> Unit
                }
            }
        }

        fun keep(events: List<WorldEvent>) {
            expectations.filterIsInstance<Expectation.Positive>().forEach { p -> matchedAt[p]?.let { p.keep(events[it]) } }
        }

        fun settle(i: Int, event: WorldEvent.Settled) {
            settledAt = i
            settled = event
        }

        val verdict: Verdict get() = when {
            violation != null || brokenBy.isNotEmpty() -> Verdict.Failed
            settledAt != null -> if (expectations.all(::holds)) Verdict.Passed else Verdict.Failed
            expectations.all { it is Expectation.Positive || it is Expectation.InOrder } &&
                expectations.all { it in matchedAt } -> Verdict.Passed
            else -> Verdict.Pending
        }

        private fun holds(expectation: Expectation): Boolean = when (expectation) {
            is Expectation.Positive, is Expectation.InOrder -> expectation in matchedAt
            is Expectation.Never -> expectation !in brokenBy
            is Expectation.Times -> (counts[expectation] ?: 0) == expectation.n
            is Expectation.SettledWith -> settled?.let(expectation.that) == true
        }

        fun outcome(expectation: Expectation): String {
            val label = expectation.label
            return when {
                expectation is Expectation.Never && expectation in brokenBy -> "✗ $label — broken by #${brokenBy[expectation]}"
                expectation is Expectation.Times -> "${mark(expectation)} $label — matched ${counts[expectation] ?: 0} time(s)"
                expectation in matchedAt -> "✓ $label — matched #${matchedAt[expectation]}"
                settledAt == null -> "… $label — not yet"
                holds(expectation) -> "✓ $label — held up to the settle at #$settledAt"
                else -> "✗ $label — not met by the settle at #$settledAt"
            } + (violation?.let { " (a lane rule broken at #$it)" } ?: "")
        }

        private fun mark(expectation: Expectation) = if (settledAt != null && holds(expectation)) "✓" else "✗"
    }
}

/** Events as a failure prints them: numbered, each on its line, long ones cut. */
internal fun List<WorldEvent>.render(): String = withIndex().joinToString("\n") { (i, event) ->
    val text = when (event) {
        is WorldEvent.UiChanged -> "UiChanged(${event.ui.layer})"
        else -> event.toString()
    }
    "  #$i ${text.take(RENDERED_CHARS)}"
}

private const val RENDERED_CHARS = 240

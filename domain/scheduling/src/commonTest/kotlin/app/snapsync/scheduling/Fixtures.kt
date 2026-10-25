@file:OptIn(CompositionOnly::class, ExperimentalCoroutinesApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.inbound.Running
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.EntryEvent
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The operating system's background time, as a test plays it: every hold, and which are still open. */
class FakeBackgroundTime(private val entries: RecordingEntryEvents? = null) : BackgroundTime {
    val holds = mutableListOf<Hold>()

    /** The next hold is begun with its time already up: its expiry fires before `begin` returns. */
    var upOnArrival = false

    override fun begin(label: String, expiry: Running): BackgroundTimeHold = Hold(
        label,
        expiry,
        entries?.told?.size,
    ).also {
        holds += it
        if (upOnArrival) expiry.expire()
    }

    /** [toldBefore]: how many entry events had been told when it was begun. */
    class Hold(val label: String, val expiry: Running, val toldBefore: Int?) : BackgroundTimeHold {
        var ended = false

        override fun end() {
            ended = true
        }
    }
}

/** One slot, as a single-threaded test sees the calling thread's. */
class FakeThreadLabel : ThreadLabel {
    var label: String? = null
    val seen = mutableListOf<String?>()

    override fun swap(label: String?): String? = this.label.also {
        this.label = label
        seen += label
    }

    override fun current(): String? = label
}

/** Every entry event the runtime told, in order. */
class RecordingEntryEvents : EntryEvents {
    val told = mutableListOf<EntryEvent>()

    override fun tell(event: EntryEvent) {
        told += event
    }

    /** What was told, as `<kind> <entry>[ <outcome>]` — the run ids and durations left out. */
    fun seen(): List<String> = told.map {
        when (it) {
            is EntryEvent.Delivered -> "delivered ${it.entry}"
            is EntryEvent.Answered -> "answered ${it.entry}"
            is EntryEvent.Done -> "done ${it.entry} ${it.outcome}"
        }
    }
}

/** Every line the log was handed. */
class RecordingLog : LogWriter {
    val lines = mutableListOf<LogLine>()

    override fun write(line: LogLine) {
        lines += line
    }

    fun events(): List<String> = lines.map { it.event }

    fun install() = Log.install(listOf(this))
}

/** A completion that remembers being answered, and how often. */
class CountingCompletion : Completion {
    var answers = 0

    override fun complete() {
        answers++
    }
}

/** A runtime over the test scheduler, with its fakes. */
class World(val test: TestScope, grace: Duration = 1.seconds, holdsTime: Boolean = true) {
    val entries = RecordingEntryEvents()
    val backgroundTime = FakeBackgroundTime(entries)
    val label = FakeThreadLabel()
    val log = RecordingLog().also { it.install() }
    private val lane = StandardTestDispatcher(test.testScheduler)
    val runtime = Runtime(
        test.backgroundScope.coroutineContext[kotlinx.coroutines.Job],
        lane,
        RuntimePorts(backgroundTime.takeIf { holdsTime }, label, entries),
        grace,
    )
}

/**
 * Runs everything the runtime has due, delays included. The runtime's scope is the test's background scope, whose work
 * `advanceUntilIdle` does not wait for.
 */
fun TestScope.settle() {
    testScheduler.advanceTimeBy(kotlin.time.Duration.parse("1h"))
    testScheduler.runCurrent()
}

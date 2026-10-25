package app.snapsync.scheduling

import app.snapsync.inbound.Answered
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Granted
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running
import app.snapsync.inbound.Trigger

/**
 * The entry point of one operating-system trigger whose work runs **after** the delivery returns
 * (`docs/patterns.md`, "Entry points"): named for the trigger (`OnSilentPush`), it implements [run] — a short list of
 * steps that orders and never decides — and nothing else. The final [handle] gives every trigger the same label,
 * containment, background time and expiry by construction: the run holds the process's background time, whose end
 * expires it.
 */
@OptIn(InboundImplementation::class)
abstract class BackgroundEntryPoint<A>(private val runtime: Runtime) : Background<A> {
    protected abstract suspend fun run(arg: A)

    final override fun handle(arg: A, completion: Completion) {
        runtime.start(Trigger.of(name), OsTime.HELD, completion) { run(arg) }
    }

    private val name: String get() = this::class.simpleName.toString()
}

/**
 * [BackgroundEntryPoint] for a delivery that is itself the operating system's grant of time — a scheduled wake: the run
 * holds no background time of its own, and the [Running] it answers is what the adapter expires when the grant is
 * withdrawn.
 */
@OptIn(InboundImplementation::class)
abstract class GrantedEntryPoint<A>(private val runtime: Runtime) : Granted<A> {
    protected abstract suspend fun run(arg: A)

    final override fun handle(arg: A, completion: Completion): Running =
        runtime.start(Trigger.of(name), OsTime.GRANTED, completion) { run(arg) }

    private val name: String get() = this::class.simpleName.toString()
}

/**
 * The entry point of a delivery the platform makes once, synchronously, and does not repeat: [run] executes on the
 * delivering thread, labelled and contained, and **persists what the delivery means before it returns**. What it
 * starts without awaiting it starts through its [Delivery], so its `done` waits for it.
 */
@OptIn(InboundImplementation::class)
abstract class InboundEntryPoint<A>(private val runtime: Runtime) : Inbound<A, Unit> {
    protected abstract fun Delivery.run(arg: A)

    /** Which of its kind [arg] is, told on the delivery's `delivered` — `null`, unless the kind has several. */
    protected open fun about(arg: A): String? = null

    final override fun deliver(arg: A) = runtime.inline(name, about(arg)) { it.run(arg) }

    private val name: String get() = this::class.simpleName.toString()
}

/**
 * The entry point of a delivery answered synchronously after work that suspends — the upload extension's
 * `process()`: the delivering thread waits for [run]'s answer, and a failure answers [onFailure].
 */
@OptIn(InboundImplementation::class)
abstract class AnsweredEntryPoint<A, R>(private val runtime: Runtime, private val onFailure: R) : Answered<A, R> {
    protected abstract suspend fun run(arg: A): R

    final override fun answer(arg: A): R = runtime.blocking(name, onFailure) { run(arg) }

    private val name: String get() = this::class.simpleName.toString()
}

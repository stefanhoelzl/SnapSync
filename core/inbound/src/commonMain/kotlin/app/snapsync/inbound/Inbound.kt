package app.snapsync.inbound

/**
 * Implementing one of the inbound kinds ([Inbound], [Background], [Granted], [Answered]). Only the entry-point bases in
 * `scheduling` opt in — and the host zone at its pinned sites, the screen's intents and its live state
 * (`ComposedApp`) — so every delivery the platform makes reaches the core through a base that labels, traces and
 * contains it (`docs/patterns.md`, "Entry points"). Test equipment that plays the core opts in freely.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Only scheduling's entry-point bases implement an inbound kind; subclass one of them instead.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class InboundImplementation

/**
 * Wiring a delivery to the core: calling an event port's `inbound(…)`, building its handler bundle, installing the
 * log's writers. Only a composition does that, once per process, after the graph it delivers into is built
 * (`docs/patterns.md`, "Composition").
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Only a composition wires deliveries; this is called once, after the graph is built.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
annotation class CompositionOnly

/**
 * The app process's alone: the upload extension must never construct or reach it. Both processes link the core, so
 * linkage cannot keep it out; the extension's 32 MB limit — overrun is a jetsam kill and a relaunch loop, not an error
 * — and its holding nothing across `process()` calls can. The app's composition opts in, once, where it binds it
 * (`OptInPinsTest`; `docs/patterns.md`, "Opt-in capabilities").
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "The upload extension must not reach this: compose it in the app's uploader only.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
annotation class AppProcessOnly

/**
 * A delivery the platform makes **once and synchronously**, on its own thread, and may answer with [R]: a transfer's
 * end, an import's placeholder or settlement, a metric report, a lifecycle notice. The receiver persists what the
 * delivery means **before it returns** — there is no second delivery.
 *
 * A plain interface, never a `fun interface`: no lambda converts to it, so behaviour cannot be passed around in its
 * place.
 */
@SubclassOptInRequired(InboundImplementation::class)
interface Inbound<A, R> {
    fun deliver(arg: A): R
}

/** The operating system's "done" handle for one delivery: answered exactly once. */
interface Completion {
    fun complete()

    /** The handle of a delivery nobody waits on: a scene coming to the front, work started inside the process. */
    companion object None : Completion {
        override fun complete() = Unit
    }
}

/**
 * A run a [Granted] delivery started, as the core hands it back to the adapter that delivered it. The adapter calls
 * [expire] when the operating system withdraws the time it granted — expiry is a delivery like any other, so the
 * adapter calls in.
 */
interface Running {
    fun expire()
}

/**
 * A delivery the platform answers **later**, through [Completion]: a silent push, a relaunch for a session's events, a
 * scene becoming active. The receiver starts the work and returns at once; the work answers the completion when it
 * ends. Its time is the process's own background time, which the receiver holds — so its expiry reaches the work
 * through that hold, never through the adapter.
 */
@SubclassOptInRequired(InboundImplementation::class)
interface Background<A> {
    fun handle(arg: A, completion: Completion)
}

/**
 * A delivery that is itself the operating system's **grant** of time — a scheduled wake — answered later through
 * [Completion]. The platform may withdraw the grant: the adapter then expires the [Running] the delivery answered, and
 * the work answers the completion once its cancellation has unwound.
 */
@SubclassOptInRequired(InboundImplementation::class)
interface Granted<A> {
    fun handle(arg: A, completion: Completion): Running
}

/**
 * A delivery the platform makes synchronously and expects **answered on its own thread** after work that suspends:
 * the upload extension's `process()`. The receiver blocks that thread until the answer is known.
 */
@SubclassOptInRequired(InboundImplementation::class)
interface Answered<A, R> {
    fun answer(arg: A): R
}

package app.snapsync.model

/**
 * Marks a **platform entry point**: a declaration the platform itself calls into — an OS callback, a
 * Swift shell forwarding one, or a user tap crossing the command door — as opposed to anything our
 * own Kotlin reaches (spec `diagnostic-logging`, "Uniform platform-invocation logging"; spec
 * `module-architecture`, "Absence is never silent").
 *
 * What distinguishes an entry point is **who is on the other side of the call**. That is why a
 * read-model property presentation polls is not one, while the platform's request for the root view
 * is.
 *
 * Where a process's OS entries form an **inbound port** (`ports/PlatformEntries`, `ports/ExtensionEntries` —
 * spec `module-architecture`, "OS entry points cross an inbound port"), the marker sits on the port's members,
 * and the obligation below falls on the core's implementation of them; the composition root reaches them by
 * delegation and holds no body to annotate. `onOpenUrl` is one of those members: the platform's link
 * deliveries reach it through the tested activity filter.
 *
 * The marker is inert — Kotlin annotations execute nothing, so this cannot instrument anything by
 * itself — and **nothing checks it**. A guard once derived the entry-point population from source and
 * asserted each carried this marker and opened with the logging wrapper; it was retired (commit
 * `74302d2b`) because it guarded diagnosability rather than behaviour, and `diagnostic-logging` states the
 * obligation is now kept by review. A missing annotation fails no build: it is documentation of an
 * obligation a reviewer looks for, not a proof that the obligation is met.
 *
 * The obligation it marks: **log the raw inputs before any decision, and name the outcome on exit.**
 * A platform callback that decides and returns without recording anything is indistinguishable in a
 * device log from one the platform never made — which is precisely how Bugsink `SNAPSYNC-3` became
 * undiagnosable.
 *
 * **A second obligation applies to some of these, and this guard does not check it** (spec
 * `module-architecture`, "State and authority"): an entry point that receives a delivery the platform
 * makes **once** must persist it *before returning*, citing the proof that it is once-only. It is a review
 * criterion rather than a gate because no syntactic rule separates a once-only fact from legitimate
 * coordination state — `terminal += Terminal(…)` and `outstandingImports += scope.launch { … }` are the
 * same shape, and only one of them was a bug (Bugsink `SNAPSYNC-11`). Scheduling the write does not
 * satisfy it: once the callback returns, the process's continued runtime is not ours to assume.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PlatformEntry

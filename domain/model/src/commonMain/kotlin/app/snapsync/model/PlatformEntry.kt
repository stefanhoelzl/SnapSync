package app.snapsync.model

/**
 * Marks a **platform entry point**: a declaration the platform itself calls into — an OS callback, a
 * Swift shell forwarding one, or a user tap crossing the command door — as opposed to anything our
 * own Kotlin reaches (spec `privacy-security`, "Uniform platform-invocation logging"; spec
 * `docs/architecture.md`, "Absence is never silent").
 *
 * What distinguishes an entry point is **who is on the other side of the call**. That is why a
 * read-model property presentation polls is not one, while the platform's request for the root view
 * is.
 *
 * The OS entries reach the core through **entry ports** (`docs/architecture.md`, "Events arrive through
 * `listen`"): the marker sits on each adapter's `deliver…` methods and on the root's one-line forwarders, and the
 * obligation below falls on the composition's handlers they deliver to.
 *
 * The marker is inert — Kotlin annotations execute nothing, so this cannot instrument anything by
 * itself — and **nothing checks it**. A guard once derived the entry-point population from source and
 * asserted each carried this marker and opened with the logging wrapper; it was retired (commit
 * `74302d2b`) because it guarded diagnosability rather than behaviour, and `privacy-security` states the
 * obligation is now kept by review. A missing annotation fails no build: it is documentation of an
 * obligation a reviewer looks for, not a proof that the obligation is met.
 *
 * The obligation it marks: **log the raw inputs before any decision, and name the outcome on exit.**
 * A platform callback that decides and returns without recording anything is indistinguishable in a
 * device log from one the platform never made — which is precisely how Bugsink `SNAPSYNC-3` became
 * undiagnosable.
 *
 * **A second obligation applies to some of these, and this guard does not check it** (spec
 * `docs/architecture.md`, "State and authority"): an entry point that receives a delivery the platform
 * makes **once** must persist it *before returning*, citing the proof that it is once-only. It is a review
 * criterion rather than a gate because no syntactic rule separates a once-only fact from legitimate
 * coordination state — `terminal += Terminal(…)` and `outstandingImports += scope.launch { … }` are the
 * same shape, and only one of them was a bug (Bugsink `SNAPSYNC-11`). Scheduling the write does not
 * satisfy it: once the callback returns, the process's continued runtime is not ours to assume.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PlatformEntry

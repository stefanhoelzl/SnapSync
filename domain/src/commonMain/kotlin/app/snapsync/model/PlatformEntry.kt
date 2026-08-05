package app.snapsync.model

/**
 * Marks a **platform entry point**: a declaration the platform itself calls into — an OS callback, a
 * Swift shell forwarding one, or a user tap crossing the command door — as opposed to anything our
 * own Kotlin reaches (spec `diagnostic-logging`, "Uniform platform-invocation logging"; spec
 * `module-architecture`, "Absence is never silent").
 *
 * What distinguishes an entry point is **who is on the other side of the call**. That is why a
 * read-model property presentation polls is not one, while the platform's request for the root view
 * is. `onOpenUrl` is not one either: it is reached from the activity entry and from the launch-env
 * trigger, never from the platform directly.
 *
 * The marker is inert — Kotlin annotations execute nothing, so this cannot instrument anything by
 * itself. It exists to be **checked**: the `architecture-guards` entry-point guard derives the
 * population from the source (never from these annotations, which would inherit the hand-enumeration
 * hole this whole change exists to close) and asserts that each derived entry point carries this
 * marker *and* opens with the logging wrapper. So a missing annotation is a red build, not an
 * invisible omission, and the annotation is documentation the guard keeps honest.
 *
 * The obligation it marks: **log the raw inputs before any decision, and name the outcome on exit.**
 * A platform callback that decides and returns without recording anything is indistinguishable in a
 * device log from one the platform never made — which is precisely how Bugsink `SNAPSYNC-3` became
 * undiagnosable.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PlatformEntry

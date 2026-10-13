package app.snapsync.model

/**
 * **The answers an adapter class never gives** (`docs/testing.md`, "Every adapter is covered"): the cells of its ports
 * that no binding of it can prove, because its own code cannot produce them — a constant, a variant it never builds, a
 * handler it never calls.
 *
 * Declared once, on the adapter class, never on the port: the port does not know its implementations. Each claim is
 * CHECKED on every build against the compiled code (`:test:architecture`, `NeverAnswersCheck`): a claim the code could
 * contradict is refused, naming where the answer is produced. Nothing else excuses a cell.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class NeverAnswers(vararg val claims: Never)

/**
 * One claim of [NeverAnswers], written as the port grid writes the cell (`build/reports/port-grid/port-grid.txt`).
 *
 * - [answer] with [member]: that member never gives that answer — a sealed leaf (`"FileResult.AreaUnavailable"`), an
 *   enum entry (`"AlbumKind.COLLECTION"`), or a constant (`"true"`, `"false"`, `"null"`, `"returns"`).
 * - [answer] alone: no member of the class's ports gives it.
 * - [handler] alone: the class never calls that handler (`"onChangedElsewhere"`).
 *
 * [member] is the member's name, or `"Port.member"` where two of the class's ports share it.
 */
@Target()
@Retention(AnnotationRetention.BINARY)
annotation class Never(val member: String = "", val answer: String = "", val handler: String = "")

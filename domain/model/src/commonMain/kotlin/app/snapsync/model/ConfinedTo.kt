package app.snapsync.model

/**
 * This mutable field is read and written only on [lane] (law "State reached from OS callbacks is confined",
 * `docs/architecture.md`; decision record `harden-seam-bug-classes`, D12).
 *
 * A class that receives OS callbacks — a delegate queue, a PhotoKit observer, a URLSession handler — is reached
 * from threads it does not choose. Each mutable field it holds is either confined to one named serial lane, or is a
 * thread-safe primitive (`MutableStateFlow`, `Channel`, an atomic, a `@Volatile` single-writer cell). This names
 * the lane, so a reader of the field can check every write against it, and the confinement gate in
 * `:test:architecture` refuses a field in such a class that says neither.
 *
 * Documentation, not enforcement: nothing checks at runtime that the access happens on [lane].
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ConfinedTo(val lane: String)

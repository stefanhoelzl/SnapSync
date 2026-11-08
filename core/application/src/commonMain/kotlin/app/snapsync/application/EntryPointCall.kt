package app.snapsync.application

/**
 * A use case's `invoke` (`docs/patterns.md`, "Use cases are leaves"): only an entry point calls a use case — entry
 * points orchestrate, use cases do one thing and never call another. Each entry point opts in where it calls one, and
 * the opt-ins are pinned (`OptInPinsTest`).
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Only an entry point calls a use case.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class EntryPointCall

package app.snapsync.ui.components

/**
 * The variant axis of the status hero: a semantic value the call site picks at RUNTIME, not a choice
 * between components. Design-time choices stay distinct components (`PrimaryButton` rather than
 * `AppButton(role = …)`), per the design-system rules; this one arrives from UI state, so it is a value.
 *
 * An **enum**, because neither case carries anything. The type was a sealed interface of seven objects,
 * justified by a comment claiming one case "even carries a payload" — it named `Progress`, which did not
 * exist, and no case carried anything then either. A variant axis that genuinely does carry payloads
 * lives next door as [AppSyncStatus], whose `Syncing(upload, download)`, `NotStarted(startsAt)` and
 * `NeedsAccess(prompt)` are the real form of that argument. If a case here ever needs data, this becomes
 * a sealed interface again and the two call sites move with it.
 *
 * The other five cases — `Success`, `Waiting`, `Photos`, `InProgress`, `Complete` — were removed once
 * measurement showed nothing constructed them: no screen, no test, no harness preset. They had been
 * abandoned one redesign at a time, surviving even a commit that set out to sweep dead components,
 * because a case with no callers still compiles as long as the renderer's `when` names it.
 */
enum class StatusIndicator {
    /** Indeterminate spinner: work with no measurable progress (e.g. reading persisted state). */
    Loading,

    /** A fault the user is being told about, rendered by the error banner. */
    Error,
}

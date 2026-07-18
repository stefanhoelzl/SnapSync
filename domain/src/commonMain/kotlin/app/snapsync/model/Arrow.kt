package app.snapsync.model

/**
 * A sync direction arrow's render state: absent, shown-idle, or shown-and-animating (spec
 * `sync-status-screen`). Shown tracks completeness (work remains), pulsing tracks live activity —
 * each arrow derives from its own counts alone. Seated in `model/` (migration step 9, the
 * Arrow/ArrowLevel unification): the one declaration both `:ui:presentation` (`SyncHealth.Syncing`)
 * and `:ui:components` (`AppSyncStatus.Syncing`) render from, so the two can never drift —
 * presentation is Compose-free and components is presentation-free, so `model/` is the only zone
 * both may name.
 */
enum class Arrow { HIDDEN, STATIC, PULSING }

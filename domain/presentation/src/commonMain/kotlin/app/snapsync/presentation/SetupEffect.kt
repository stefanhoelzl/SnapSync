package app.snapsync.presentation

/**
 * One-shot effects the container emits to the UI (the side-effect channel). The only one is the
 * transient invalid-link error: an event link arrived that the decoder rejected, so the create screen
 * flashes a self-clearing message on its inline error line without changing persisted state.
 */
sealed interface SetupEffect {
    data object InvalidConfigLink : SetupEffect
}

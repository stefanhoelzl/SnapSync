package app.snapsync.presentation

/**
 * One-shot effects the setup gate emits to the UI (the container's side-effect channel). The only
 * one in v1 is the transient invalid-link error: a deeplink arrived that the decoder rejected, so
 * the screen flashes a self-clearing message on the storage card without changing persisted state.
 */
sealed interface SetupEffect {
    data object InvalidConfigLink : SetupEffect
}

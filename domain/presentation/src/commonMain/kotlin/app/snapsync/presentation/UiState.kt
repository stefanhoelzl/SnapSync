package app.snapsync.presentation

sealed interface UiState {
    data object Idle : UiState

    data class Uploading(val done: Int, val total: Int) : UiState
}

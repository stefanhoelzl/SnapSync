package app.snapsync.presentation

import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
import kotlinx.coroutines.CoroutineScope
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class StatusContainerHost(
    source: SyncStatusSource,
    scope: CoroutineScope,
) : ContainerHost<UiState, Nothing> {

    override val container: Container<UiState, Nothing> =
        scope.container(UiState.Idle) {
            intent {
                source.status.collect { snapshot ->
                    reduce { snapshot.toUiState() }
                }
            }
        }
}

private fun SyncStatus.toUiState(): UiState =
    if (pending == 0) UiState.Idle
    else UiState.Uploading(done = completed, total = pending + completed)

package app.snapsync.presentation

import app.snapsync.sync.SyncStatus
import app.snapsync.sync.SyncStatusSource
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test

private class FakeSyncStatusSource : SyncStatusSource {
    // replay = 1: emissions are never lost to the subscription race with the
    // container's onCreate collector, while suspending backpressure still
    // delivers every snapshot (no conflation) for deterministic assertions.
    override val status = MutableSharedFlow<SyncStatus>(replay = 1)
}

class StatusContainerHostTest {

    @Test
    fun `no pending uploads shows Idle`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.emit(SyncStatus(pending = 7, completed = 3))
            expectState(UiState.Uploading(done = 3, total = 10))
            source.status.emit(SyncStatus(pending = 0, completed = 10))
            expectState(UiState.Idle)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `pending uploads show progress X of N`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.emit(SyncStatus(pending = 7, completed = 3))
            expectState(UiState.Uploading(done = 3, total = 10))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        StatusContainerHost(source, backgroundScope).test(this) {
            runOnCreate()
            source.status.emit(SyncStatus(pending = 9, completed = 1))
            expectState(UiState.Uploading(done = 1, total = 10))
            source.status.emit(SyncStatus(pending = 5, completed = 5))
            expectState(UiState.Uploading(done = 5, total = 10))
            cancelAndIgnoreRemainingItems()
        }
    }
}

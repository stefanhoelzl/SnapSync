package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.PreferencesContract
import app.snapsync.contracts.PreferencesState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.FileArea
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences
import kotlin.test.Test

/** The storage mocks bound to the same contracts as the real adapters — their licence to stand in for them. */
class StorageMockContractBindingsTest {

    private val files = object : Binding<FilesState, Files> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(FilesState.EMPTY, FilesState.HOLDING, FilesState.DENIED, FilesState.UNAVAILABLE)
        override fun create(state: FilesState, clauseId: String): Entered<Files> {
            val path = FilesContract.path(clauseId)
            return Entered.Ready(
                when (state) {
                    FilesState.EMPTY -> inMemoryFiles()
                    FilesState.HOLDING -> inMemoryFiles(shared = mutableMapOf(path to FilesContract.seed(clauseId)))
                    FilesState.DENIED -> inMemoryFiles(
                        shared = mutableMapOf(path to FilesContract.seed(clauseId)),
                        denied = setOf(FileArea.SHARED to path),
                    )
                    FilesState.UNAVAILABLE -> inMemoryFiles(shared = null)
                },
            )
        }
    }

    private val preferences = object : Binding<PreferencesState, Preferences> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PreferencesState.EMPTY, PreferencesState.HOLDING)
        override fun create(state: PreferencesState, clauseId: String): Entered<Preferences> = Entered.Ready(
            when (state) {
                PreferencesState.EMPTY -> inMemoryPreferences()
                PreferencesState.HOLDING ->
                    inMemoryPreferences(mutableMapOf(PreferencesContract.key(clauseId) to PreferencesContract.seed(clauseId)))
            },
        )
    }

    @Test
    fun `the in-memory files satisfy the Files contract`() = verify(FilesContract, files)

    @Test
    fun `the in-memory preferences satisfy the Preferences contract`() = verify(PreferencesContract, preferences)
}

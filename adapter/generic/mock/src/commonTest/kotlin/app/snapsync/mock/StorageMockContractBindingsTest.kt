package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.PreferencesContract
import app.snapsync.contracts.PreferencesState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
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
        override fun create(state: FilesState, clauseId: String, log: CallLog): Entered<Files> {
            val path = FilesContract.path(clauseId)
            return when (state) {
                FilesState.EMPTY -> Entered.Ready(inMemoryFiles())
                FilesState.HOLDING -> Entered.Ready(
                    inMemoryFiles(shared = mutableMapOf(path to FilesContract.seed(clauseId))),
                )
                FilesState.DENIED -> Entered.Ready(
                    inMemoryFiles(
                        shared = mutableMapOf(path to FilesContract.seed(clauseId)),
                        denied = setOf(FileArea.SHARED to path),
                    ),
                )
                FilesState.UNAVAILABLE -> Entered.Ready(inMemoryFiles(shared = null))
                // The double holds paths, not directories with permissions, and no links.
                FilesState.DENIED_DIRECTORY,
                FilesState.READ_ONLY_DIRECTORY,
                FilesState.UNSEARCHABLE_DIRECTORY,
                FilesState.LOOPED_LINK,
                -> Entered.Unreachable("the double holds no directories with permissions, and no links")
            }
        }
    }

    private val preferences = object : Binding<PreferencesState, Preferences> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PreferencesState.EMPTY, PreferencesState.HOLDING)
        override fun create(state: PreferencesState, clauseId: String, log: CallLog): Entered<Preferences> = when (state) {
            PreferencesState.EMPTY -> Entered.Ready(inMemoryPreferences())
            PreferencesState.HOLDING -> Entered.Ready(
                inMemoryPreferences(
                    mutableMapOf(PreferencesContract.key(clauseId) to PreferencesContract.seed(clauseId)),
                ),
            )
            PreferencesState.FOREIGN, PreferencesState.UNWRITABLE ->
                Entered.Unreachable("the double holds strings only, and refuses no write")
        }
    }

    @Test
    fun `the in-memory files satisfy the Files contract`() = verify(FilesContract, files)

    @Test
    fun `the in-memory preferences satisfy the Preferences contract`() = verify(PreferencesContract, preferences)
}

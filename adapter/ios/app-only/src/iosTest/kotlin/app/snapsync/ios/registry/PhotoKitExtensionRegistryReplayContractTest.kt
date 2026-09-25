package app.snapsync.ios.registry

import app.snapsync.contract.RECORDINGS
import app.snapsync.contract.ReplayingRegistrationApi
import app.snapsync.contract.registryInState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.UploadExtensionRegistryContract
import app.snapsync.contracts.UploadExtensionRegistryState
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.verify
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.UploadExtensionRegistry
import kotlin.test.Test

/**
 * The device's extension registration, REPLAYED (`docs/architecture.md`): the CURRENT
 * [PhotoKitExtensionRegistry] runs against what iOS answered when the two recordings were taken — one under a full
 * photo grant, one under a partial grant — and the current clauses judge.
 *
 * An adapter that asks iOS something else reads `Diverged`: re-record on the device under both grants (the
 * `rig-channel` runbook). A recorded answer that violates a clause reads `Failed`, which re-recording does not fix.
 */
class PhotoKitExtensionRegistryReplayContractTest {

    private fun replay(grant: PermissionStatus, state: UploadExtensionRegistryState, clauseId: String): Entered<UploadExtensionRegistry> {
        val name = recordingName(UploadExtensionRegistryContract.name, Host.IOS_DEVICE_APP, grant)
        val tape = RECORDINGS[name]?.let(Recording::parse)
            ?: return Entered.Unreachable("no recording $name.rec — record it on a device over the rig")
        val block = tape.blocks[clauseId]
            ?: return Entered.Unreachable("$name.rec holds no block for $clauseId — re-record")
        val replayer = Replayer(clauseId, block)
        return registryInState(ReplayingRegistrationApi(replayer), state, afterDispose = replayer::assertExhausted)
    }

    private val granted = object : Binding<UploadExtensionRegistryState, UploadExtensionRegistry> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val grant = PermissionStatus.GRANTED
        override val reaches = setOf(UploadExtensionRegistryState.RECORD_ABSENT, UploadExtensionRegistryState.RECORD_PRESENT)

        override fun create(state: UploadExtensionRegistryState, clauseId: String): Entered<UploadExtensionRegistry> =
            if (state in reaches) replay(PermissionStatus.GRANTED, state, clauseId) else Entered.Unreachable("recorded under a partial grant")
    }

    private val limited = object : Binding<UploadExtensionRegistryState, UploadExtensionRegistry> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val grant = PermissionStatus.LIMITED
        override val reaches = setOf(UploadExtensionRegistryState.UNDER_PARTIAL_GRANT)

        override fun create(state: UploadExtensionRegistryState, clauseId: String): Entered<UploadExtensionRegistry> =
            if (state in reaches) replay(PermissionStatus.LIMITED, state, clauseId) else Entered.Unreachable("recorded under a full grant")
    }

    @Test
    fun `the recorded device registry under a full grant satisfies the contract`() =
        verify(UploadExtensionRegistryContract, granted)

    @Test
    fun `the recorded device registry under a partial grant satisfies the contract`() =
        verify(UploadExtensionRegistryContract, limited)
}

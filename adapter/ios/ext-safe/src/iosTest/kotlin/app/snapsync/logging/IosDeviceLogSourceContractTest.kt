package app.snapsync.logging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceLogSourceContract
import app.snapsync.contracts.DeviceLogSourceState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceLogSource.Process
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.writeTextFile
import kotlin.test.Test

/**
 * The device-log tail reader, live (`docs/architecture.md`), over two log paths in a fresh directory —
 * on a device one is `Documents/debug.log` and the other the App-Group `ext-debug.log`; the reader is given
 * both, and seeks the same way over either. The rolled state writes only each log's `.1` sibling, exactly
 * as `FileLogWriter` names it.
 */
class IosDeviceLogSourceContractTest {

    private val binding = object : Binding<DeviceLogSourceState, DeviceLogSource> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DeviceLogSourceState.NO_LOG,
            DeviceLogSourceState.EMPTY_LOG,
            DeviceLogSourceState.HOLDING,
            DeviceLogSourceState.ROLLED_ONLY,
        )

        override fun create(state: DeviceLogSourceState, clauseId: String): Entered<DeviceLogSource> {
            val dir = newTempDirectory()
            val paths = mapOf(Process.APP to "$dir/debug.log", Process.EXTENSION to "$dir/ext-debug.log")
            paths.forEach { (process, path) ->
                when (state) {
                    DeviceLogSourceState.NO_LOG -> Unit
                    DeviceLogSourceState.EMPTY_LOG -> writeTextFile(path, "")
                    DeviceLogSourceState.HOLDING -> writeTextFile(path, DeviceLogSourceContract.seedLog(process, clauseId))
                    DeviceLogSourceState.ROLLED_ONLY ->
                        writeTextFile("$path.1", DeviceLogSourceContract.seedLog(process, clauseId))
                }
            }
            return Entered.Ready(IosDeviceLogSource(paths.getValue(Process.APP), paths.getValue(Process.EXTENSION))) {
                removeDirectory(dir)
            }
        }
    }

    @Test
    fun `the device-log reader satisfies the DeviceLogSource contract`() = verify(DeviceLogSourceContract, binding)
}

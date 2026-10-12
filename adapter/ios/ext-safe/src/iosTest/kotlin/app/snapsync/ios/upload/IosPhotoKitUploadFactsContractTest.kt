package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.OsUploadQueueContract
import app.snapsync.contracts.OsUploadQueueState
import app.snapsync.contracts.QueueUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.GalleryResourceHandle
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The real [IosPhotoKitUploadPlatform] in the simulator's test executable, for the state that asks the system nothing:
 * a resource of another library, a job it never presented or offered. Everything it sends is the upload extension's,
 * recorded on a device.
 */
class IosPhotoKitUploadFactsContractTest {

    private val binding = object : Binding<OsUploadQueueState, QueueUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(OsUploadQueueState.ANY)

        override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
            if (state !in reaches) return Entered.Unreachable("the PhotoKit queue runs only in the upload extension")
            return Entered.Ready(
                QueueUnderTest(
                    queue = IosPhotoKitUploadPlatform(Logger.withTag("contract")).recorded(log),
                    base = "",
                    usable = { error("this state creates nothing") },
                    unusable = { object : GalleryResourceHandle {} },
                    objects = FixtureObjects { null },
                ),
            )
        }
    }

    @Test
    fun `the PhotoKit uploader satisfies the OsUploadQueue contract's facts`() = verify(OsUploadQueueContract, binding)
}

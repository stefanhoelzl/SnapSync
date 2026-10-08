package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.UploadSource
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The real [IosPhotoKitUploadPlatform] in the simulator's test executable, for the states that ask the system
 * nothing: what its uploads take, and a job it never presented. Everything it sends is the upload extension's,
 * recorded on a device.
 */
class IosPhotoKitUploadFactsContractTest {

    private val binding = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(UploadState.TAKES_RESOURCES_ONLY, UploadState.PRESENTS_WHEN_ASKED)

        override fun create(state: UploadState, clauseId: String, log: CallLog): Entered<UploadUnderTest> {
            if (state !in reaches) return Entered.Unreachable("the PhotoKit queue runs only in the upload extension")
            return Entered.Ready(
                UploadUnderTest(
                    upload = IosPhotoKitUploadPlatform(Logger.withTag("contract")).recorded(log),
                    base = "",
                    usable = { error("this state creates nothing") },
                    unusable = { UploadSource.File("/nonexistent/$it") },
                    ended = { emptyList() },
                    objects = FixtureObjects { null },
                ),
            )
        }
    }

    @Test
    fun `the PhotoKit uploader satisfies the Upload contract's facts`() = verify(UploadContract, binding)
}

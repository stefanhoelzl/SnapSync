package app.snapsync.android.systemui

import android.app.Application
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.android.upload.AndroidExtensionRegistry
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.verify
import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.SystemUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The system-UI contracts against [AndroidSystemUi] — started as a new task, as with no activity in front — and the
 * extension registry's one answer, pinned beside it as `ExtensionRegistryContract` asks of a platform without one.
 */
class AndroidSystemUiContractTest {

    private fun systemUi() = AndroidSystemUi(context, ForegroundActivity(context.applicationContext as Application))

    private val share = object : Binding<SharePresenterState, SystemUi> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(SharePresenterState.PRESENTABLE)
        override fun create(state: SharePresenterState, clauseId: String): Entered<SystemUi> = Entered.Ready(systemUi())
    }

    private val links = object : Binding<LinkOpenerState, SystemUi> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)
        override fun create(state: LinkOpenerState, clauseId: String): Entered<SystemUi> = Entered.Ready(systemUi())
    }

    @Test
    fun `the system share sheet satisfies the SharePresenter contract`() = verify(SharePresenterContract, share)

    @Test
    fun `ACTION_VIEW satisfies the LinkOpener contract`() = verify(LinkOpenerContract, links)

    @Test
    fun `Android has no upload extension to register`() = runTest {
        assertEquals(RegistrationAnswer.Unsupported, AndroidExtensionRegistry.setEnabled(true))
        assertEquals(RegistrationAnswer.Unsupported, AndroidExtensionRegistry.setEnabled(false))
        assertEquals(RegistrationState.UNSUPPORTED, AndroidExtensionRegistry.isEnabled())
    }
}

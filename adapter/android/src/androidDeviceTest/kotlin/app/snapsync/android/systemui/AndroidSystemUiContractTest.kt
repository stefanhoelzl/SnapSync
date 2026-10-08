package app.snapsync.android.systemui

import android.app.Application
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.android.storage.deviceShell
import app.snapsync.android.upload.AndroidExtensionRegistry
import app.snapsync.contracts.AppSettingsContract
import app.snapsync.contracts.AppSettingsState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionRegistryContract
import app.snapsync.contracts.ExtensionRegistryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.ShownSettings
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.SystemUi
import kotlin.test.Test

/**
 * The system-UI contracts against [AndroidSystemUi] — started as a new task, as with no activity in front — and the
 * extension registry, which on a platform without the mechanism answers `Unsupported` either way.
 */
class AndroidSystemUiContractTest {

    private fun systemUi() = AndroidSystemUi(context, ForegroundActivity(context.applicationContext as Application))

    private val share = object : Binding<SharePresenterState, SystemUi> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(SharePresenterState.PRESENTABLE)
        override fun create(state: SharePresenterState, clauseId: String, log: CallLog): Entered<SystemUi> =
            if (state in reaches) {
                Entered.Ready(systemUi())
            } else {
                Entered.Unreachable(
                    "Android starts the sheet as a task of its own: there is always somewhere to present it",
                )
            }
    }

    private val links = object : Binding<LinkOpenerState, SystemUi> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)
        override fun create(state: LinkOpenerState, clauseId: String, log: CallLog): Entered<SystemUi> =
            Entered.Ready(systemUi().recorded(log))
    }

    @Test
    fun `the system share sheet satisfies the SharePresenter contract`() = verify(SharePresenterContract, share)

    @Test
    fun `ACTION_VIEW satisfies the LinkOpener contract`() = verify(LinkOpenerContract, links)

    private val registry = object : Binding<ExtensionRegistryState, ExtensionRegistry> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(ExtensionRegistryState.NO_MECHANISM)
        override fun create(state: ExtensionRegistryState, clauseId: String): Entered<ExtensionRegistry> =
            if (state in reaches) {
                Entered.Ready(AndroidExtensionRegistry)
            } else {
                Entered.Unreachable("Android has no upload extension, so no record and no grant to refuse it under")
            }
    }

    private val settings = object : Binding<AppSettingsState, ShownSettings> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(AppSettingsState.SHOWABLE)
        override fun create(state: AppSettingsState, clauseId: String): Entered<ShownSettings> =
            Entered.Ready(
                ShownSettings(
                    ui = systemUi(),
                    // The resumed activity is what the screen shows: Settings' own package, on this app's details page.
                    settingsInFront = { SETTINGS_PACKAGE in resumedActivity() },
                    leave = { deviceShell("input keyevent KEYCODE_HOME") },
                ),
            )
    }

    private fun resumedActivity(): String =
        deviceShell("dumpsys activity activities").lineSequence().firstOrNull { "ResumedActivity" in it }.orEmpty()

    @Test
    fun `Android satisfies the extension registry contract by having no extension`() =
        verify(ExtensionRegistryContract, registry)

    @Test
    fun `the application details page satisfies the AppSettings contract`() = verify(AppSettingsContract, settings)

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}

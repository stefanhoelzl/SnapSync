package app.snapsync.android.systemui

import android.app.Application
import android.content.Intent
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
        override val adapter = AndroidSystemUi::class
        override val reaches = setOf(SharePresenterState.PRESENTABLE, SharePresenterState.NO_SHEET)
        override fun create(
            state: SharePresenterState,
            clauseId: String,
            log: CallLog,
        ): Entered<SystemUi> = when (state) {
            SharePresenterState.PRESENTABLE ->
                Entered.Ready(systemUi().recorded(log), dispose = { dismissHandoff("-a ${Intent.ACTION_CHOOSER}") })
            // The system's chooser disabled: no activity handles ACTION_CHOOSER (measured, API 36).
            SharePresenterState.NO_SHEET -> {
                deviceShell("pm disable-user --user 0 $CHOOSER_PACKAGE")
                Entered.Ready(systemUi().recorded(log)) { deviceShell("pm enable $CHOOSER_PACKAGE") }
            }
            SharePresenterState.NO_WINDOW -> Entered.Unreachable(
                "Android starts the sheet as a task of its own: there is always somewhere to present it",
            )
        }
    }

    private val links = object : Binding<LinkOpenerState, SystemUi> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidSystemUi::class
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)
        override fun create(state: LinkOpenerState, clauseId: String, log: CallLog): Entered<SystemUi> =
            when (state) {
                LinkOpenerState.CLAIMED -> Entered.Ready(
                    systemUi().recorded(log),
                    dispose = {
                        dismissHandoff(
                            "-a ${Intent.ACTION_VIEW} -c ${Intent.CATEGORY_BROWSABLE} -d ${LinkOpenerContract.CLAIMED_URL}",
                        )
                    },
                )
                LinkOpenerState.UNCLAIMED -> Entered.Ready(systemUi().recorded(log))
            }
    }

    @Test
    fun `the system share sheet satisfies the SharePresenter contract`() = verify(SharePresenterContract, share)

    @Test
    fun `ACTION_VIEW satisfies the LinkOpener contract`() = verify(LinkOpenerContract, links)

    private val registry = object : Binding<ExtensionRegistryState, ExtensionRegistry> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidExtensionRegistry::class
        override val reaches = setOf(ExtensionRegistryState.NO_MECHANISM)
        override fun create(state: ExtensionRegistryState, clauseId: String, log: CallLog): Entered<ExtensionRegistry> =
            if (state in reaches) {
                Entered.Ready(AndroidExtensionRegistry.recorded(log))
            } else {
                Entered.Unreachable("Android has no upload extension, so no record and no grant to refuse it under")
            }
    }

    private val settings = object : Binding<AppSettingsState, ShownSettings> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidSystemUi::class
        override val reaches = setOf(AppSettingsState.SHOWABLE)
        override fun create(state: AppSettingsState, clauseId: String, log: CallLog): Entered<ShownSettings> =
            Entered.Ready(
                ShownSettings(
                    ui = systemUi().recorded(log),
                    // The resumed activity is what the screen shows: Settings' own package, on this app's details page.
                    settingsInFront = { SETTINGS_PACKAGE in resumedActivity() },
                    leave = { deviceShell("input keyevent KEYCODE_HOME") },
                ),
            )
    }

    /**
     * Returns the screen to the launcher after a clause handed something to another app — the app the system resolves
     * for [intent] (`cmd package resolve-activity` arguments). The hand-off answers before that app has started, and a
     * cold one keeps launching after the clause ends: Chrome's trampoline opens its first-run page a few seconds later,
     * on top of whatever the next clause brought to the front (the AppSettings clause, which then never sees Settings).
     * So wait for the app to come to the front, go home, force-stop it so nothing it started is still on its way, and
     * wait for the launcher — which the system may bring back late, over the next clause's hand-off — to be in front.
     * Every wait is bounded and none fails the clause: this only puts the screen back.
     */
    private fun dismissHandoff(intent: String) {
        val handler = resolvedPackage(intent)
        awaitInFront(handler)
        deviceShell("input keyevent KEYCODE_HOME")
        // `android` is the system's own resolver, answering when several apps claim the intent: never stop it.
        if (handler != null && handler != SYSTEM_PACKAGE) deviceShell("am force-stop $handler")
        awaitInFront(resolvedPackage("-a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}"))
    }

    private fun resolvedPackage(intent: String): String? =
        deviceShell("cmd package resolve-activity --brief $intent")
            .lineSequence().lastOrNull { "/" in it }?.substringBefore('/')?.trim()

    private fun awaitInFront(pkg: String?) {
        val deadline = System.currentTimeMillis() + HANDOFF_FRONT_MILLIS
        while (pkg != null && pkg !in resumedActivity() && System.currentTimeMillis() < deadline) {
            Thread.sleep(HANDOFF_POLL_MILLIS)
        }
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
        const val SYSTEM_PACKAGE = "android"
        const val HANDOFF_FRONT_MILLIS = 20_000L
        const val HANDOFF_POLL_MILLIS = 100L
        const val CHOOSER_PACKAGE = "com.android.intentresolver"
    }
}

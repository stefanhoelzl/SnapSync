package app.snapsync.systemui

import app.snapsync.contract.LEFT_THE_FRONT
import app.snapsync.contract.RECORDINGS
import app.snapsync.contract.ReplayingUrlOpenerApi
import app.snapsync.contracts.AppSettingsContract
import app.snapsync.contracts.AppSettingsState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ShownSettings
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import kotlin.test.Test

/**
 * The device's trip to this app's Settings page, REPLAYED (`docs/architecture.md`): the CURRENT [IosSystemUi] runs
 * against what iOS answered when `test/contracts/recordings/AppSettings@IOS_DEVICE_APP.rec` was recorded — the
 * `openURL` call and the screen the binding read — and the current clause judges. An adapter that stops asking iOS
 * through `openURL:options:completionHandler:` reads `Diverged`.
 */
class IosAppSettingsReplayContractTest {

    private val binding = object : Binding<AppSettingsState, ShownSettings> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val adapter = IosSystemUi::class
        override val reaches = setOf(AppSettingsState.SHOWABLE)

        override fun create(state: AppSettingsState, clauseId: String, log: CallLog): Entered<ShownSettings> =
            replayerFor(RECORDINGS, RECORDING, clauseId) { replayer ->
                Entered.Ready(
                    ShownSettings(
                        ui = IosSystemUi(ReplayingUrlOpenerApi(replayer)).recorded(log),
                        settingsInFront = { replayer.answer(LEFT_THE_FRONT).toBooleanStrict() },
                        leave = {},
                    ),
                    dispose = replayer::assertExhausted,
                )
            }
    }

    @Test
    fun `the recorded device satisfies the AppSettings contract`() = verify(AppSettingsContract, binding)

    private companion object {
        const val RECORDING = "AppSettings@IOS_DEVICE_APP"
    }
}

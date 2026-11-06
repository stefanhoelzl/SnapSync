package app.snapsync.presentation

import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.ui.state.SwitchTo

/** Whether the switch was turned on: what the participation form holds. */
internal val SwitchTo.isOn: Boolean
    get() = when (this) {
        SwitchTo.ON -> true
        SwitchTo.OFF -> false
    }

/** The mobile-data switch's position as the device's choice. */
internal fun SwitchTo.mobileData(): MobileDataChoice = when (this) {
    SwitchTo.ON -> MobileDataChoice.ALLOWED
    SwitchTo.OFF -> MobileDataChoice.WIFI_ONLY
}

package app.snapsync.format

import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.StoredChoice

/**
 * The **one text form of the mobile-data choice** — what the shared preferences hold: `on` or `off`. Runtime identity:
 * every device that ever chose holds one of the two, and the upload extension reads what the app wrote.
 * `MobileDataTextTest` pins them.
 */
object MobileDataText {
    private const val ON = "on"
    private const val OFF = "off"

    /** [choice] as stored. */
    fun spell(choice: MobileDataChoice): String = when (choice) {
        MobileDataChoice.ALLOWED -> ON
        MobileDataChoice.WIFI_ONLY -> OFF
    }

    /** What a stored [text] holds: `on`, `off`, or another value no build writes. */
    fun parse(text: String): StoredChoice = when (text) {
        ON -> StoredChoice.ON
        OFF -> StoredChoice.OFF
        else -> StoredChoice.OTHER
    }
}

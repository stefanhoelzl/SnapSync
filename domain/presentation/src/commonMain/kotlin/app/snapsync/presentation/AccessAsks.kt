package app.snapsync.presentation

/**
 * The three photo-access taps, grouped — they are one question the screen asks in three forms, and the screen's own
 * `AccessActions` bundle already mirrors this grouping.
 */
class AccessAsks internal constructor(private val runner: IntentRunner, private val commands: UserCommands) {
    fun onRequestPermission() = runner.intent { commands.platform.requestAccess.fire(Unit) }

    /**
     * The joined layer's "Choose more photos" tap — presents the platform's limited-library picker; the selection
     * outcome arrives via the selection seam.
     */
    fun onChoosePhotos() = runner.intent { commands.platform.choosePhotos.fire(Unit) }

    fun onOpenSettings() = runner.intent { commands.platform.openSettings.fire(Unit) }
}

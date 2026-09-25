package app.snapsync.rig

import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploaderPin
import app.snapsync.model.extensionRegistrable

/**
 * The development switch per uploader — the control channel's way to exercise one uploader alone
 * (capability `background-upload`, "A mechanism override is a runtime input a shipped build cannot carry").
 *
 * **This is the whole of the switch's state, and it lives entirely on this side of the seam.** The production
 * composition root holds only a source (`uploaderPinSource`, answering `null` by default) which the boot hook points
 * here once. Nothing in a shipped binary can reach this object, because none of `:test:rig` is compiled into a
 * build made without `-Psnapsync.rig=true` — so a production build is *unable* to carry a switch rather than
 * merely unlikely to.
 *
 * **Deliberately not durable.** The switch dies with the process, so it cannot be inherited by a build that
 * never set it.
 *
 * Mutable module state, which `:domain` would forbid and this module does not: `:test:rig` is dev equipment
 * whose whole job is to hold what an operator asked for between requests.
 */
object UploaderSwitch {

    /** What the operator switched, or `null` for "both on — let the device and the grant decide". */
    var current: UploaderPin? = null
        private set

    /** Bound into production once, by the boot hook. Read fresh at every use. */
    fun pinned(): UploaderPin? = current

    /** Set or clear the switch. Returns the value now in effect. */
    fun set(pin: UploaderPin?): UploaderPin? {
        current = pin
        return current
    }
}

/**
 * `POST /device/uploaders?app=on|off&extension=on|off` — or `?reset` to clear the switch.
 *
 * `app=off` makes the app's uploader withhold (it records, never creates); `extension=off` makes the extension
 * unregistrable, and the compared reconcile this command triggers deregisters it now — the extension cannot read
 * this process's memory, so off must be a deregistration (decision record `changes/both-uploaders-active`, D8).
 *
 * Reports the switch **and** the registration fact it produces, because the two can disagree: the extension is
 * never registrable below iOS 26.1 or without a full grant, whatever the switch says.
 */
fun uploadersCommand(
    osSupportsOsDrivenUpload: () -> Boolean,
    permission: () -> PermissionStatus,
    reconcile: suspend () -> Unit,
): RigCommand = RigCommand { params, _ ->
    val app = onOff(params["app"])
    val extension = onOff(params["extension"])
    val reset = params.containsKey("reset")
    when {
        !reset && params["app"] == null && params["extension"] == null ->
            CommandResult.badRequest("give app=on|off and/or extension=on|off, or reset")
        app == Parsed.Invalid || extension == Parsed.Invalid ->
            CommandResult.badRequest("app and extension take on|off")
        else -> {
            val previous = UploaderSwitch.current ?: UploaderPin()
            val pin = if (reset) {
                null
            } else {
                UploaderPin(
                    app = (app as? Parsed.Value)?.on ?: previous.app,
                    extension = (extension as? Parsed.Value)?.on ?: previous.extension,
                )
            }
            UploaderSwitch.set(pin)
            reconcile()
            val registrable = extensionRegistrable(osSupportsOsDrivenUpload(), permission(), pin)
            CommandResult.ok(
                """{"app":${pin?.app ?: true},"extension":${pin?.extension ?: true},""" +
                    """"extensionRegistrable":$registrable,"permission":"${permission().name}",""" +
                    """"osSupportsOsDriven":${osSupportsOsDrivenUpload()}}""",
            )
        }
    }
}

private sealed interface Parsed {
    data object Absent : Parsed
    data object Invalid : Parsed
    data class Value(val on: Boolean) : Parsed
}

private fun onOff(raw: String?): Parsed = when (raw?.lowercase()) {
    null -> Parsed.Absent
    "on" -> Parsed.Value(true)
    "off" -> Parsed.Value(false)
    else -> Parsed.Invalid
}

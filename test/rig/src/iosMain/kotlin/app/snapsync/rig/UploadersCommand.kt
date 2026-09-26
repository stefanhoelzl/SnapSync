package app.snapsync.rig

import app.snapsync.model.GalleryAccess
import app.snapsync.model.UploaderPin
import app.snapsync.model.extensionRegistrable

fun uploadersCommand(
    controls: RigDevControls,
    osSupportsOsDrivenUpload: () -> Boolean,
    permission: () -> GalleryAccess,
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
            val previous = controls.pin ?: UploaderPin()
            val pin = if (reset) {
                null
            } else {
                UploaderPin(
                    app = (app as? Parsed.Value)?.on ?: previous.app,
                    extension = (extension as? Parsed.Value)?.on ?: previous.extension,
                )
            }
            controls.pin = pin
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

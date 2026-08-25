package app.snapsync.rig

import app.snapsync.model.UploadMechanism

/**
 * The development pin on the resolved upload mechanism — the control channel's replacement for the
 * deleted `SNAPSYNC_FORCE_URLSESSION_UPLOAD` (capability `upload-lifecycle`).
 *
 * **This is the whole of the override's state, and it lives entirely on this side of the seam.** The
 * production composition root holds only a thunk (`uploadMechanismOverrideSource`, defaulting to
 * `{ null }`) which the boot hook points here once. Nothing in a shipped binary can reach this object,
 * because none of `:test:rig` is compiled into a build made without `-Psnapsync.rig=true` — so a
 * production build is *unable* to carry a pin rather than merely unlikely to.
 *
 * **Deliberately not durable.** The pin dies with the process. Both measurements it exists to unblock
 * happen inside one process, and a value that cannot outlive the process cannot be inherited by a build
 * that never set it — which is the hazard a persisted version had to defend against with a
 * process-scoping rule. If a long device session ever makes the pin evaporating at the next foreground
 * painful, durability belongs **here** (two lines of `NSUserDefaults` in the App-Group suite, restored by
 * the boot hook), never in production: the root reads through the thunk either way, so that choice does
 * not cross the seam.
 *
 * Mutable module state, which `:domain` would forbid and this module does not: `:test:rig` is dev
 * equipment whose whole job is to hold what an operator asked for between requests.
 */
object UploadMechanismPin {

    /** What the operator pinned, or `null` for "let the device and the permission decide". */
    var current: UploadMechanism? = null
        private set

    /** Bound into production once, by the boot hook. Read fresh at every resolution. */
    fun pinned(): UploadMechanism? = current

    /**
     * Set or clear the pin. Returns the value now in effect.
     *
     * Clearing is a first-class outcome rather than an absence: "no pin" and "a request that failed to
     * parse" must not look alike to an operator, so an unrecognized value is refused at the command
     * rather than silently becoming `null` here.
     */
    fun set(mechanism: UploadMechanism?): UploadMechanism? {
        current = mechanism
        return current
    }
}

/**
 * `POST /device/upload-mechanism?value=photokit|url_session|idle|none`
 *
 * Reports the pin **and** what the app resolves with it, because the two can disagree and only one of
 * them is what the app will do: a pin naming a mechanism this OS cannot run is clamped by the resolver
 * (below 26.1 the OS-driven registration selector does not exist, so honouring it would trap), and a pin
 * is ignored entirely without usable photo access. An operator who saw only the pin could not tell a
 * clamped request from an obeyed one.
 */
fun uploadMechanismCommand(
    osSupportsOsDrivenUpload: () -> Boolean,
    permission: () -> app.snapsync.model.PermissionStatus,
): RigCommand = RigCommand { params ->
    val raw = params["value"]
    val cleared = raw.equals("none", ignoreCase = true) || raw.equals("clear", ignoreCase = true)
    val named = UploadMechanism.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    when {
        raw.isNullOrBlank() -> CommandResult.badRequest(
            "value is required: ${UploadMechanism.entries.joinToString("|") { it.name.lowercase() }}|none",
        )
        !cleared && named == null -> CommandResult.badRequest(
            "value must be one of ${UploadMechanism.entries.joinToString("|") { it.name.lowercase() }}|none, " +
                "was '$raw'",
        )
        else -> {
            val pin = UploadMechanismPin.set(if (cleared) null else named)
            val resolved = app.snapsync.model.resolveUploadMechanism(
                backgroundUploadSupported = osSupportsOsDrivenUpload(),
                permission = permission(),
                override = pin,
            )
            CommandResult.ok(
                """{"pinned":${pin?.let { "\"${it.diagnosticName}\"" } ?: "null"},""" +
                    """"resolves":"${resolved.diagnosticName}",""" +
                    """"permission":"${permission().name}",""" +
                    """"osSupportsOsDriven":${osSupportsOsDrivenUpload()}}""",
            )
        }
    }
}

package app.snapsync.model

/**
 * A development switch per uploader (capability `upload-lifecycle`, "A mechanism override is a runtime input a
 * shipped build cannot carry"). On a production build it is **always** `null`: nothing in a production build can
 * supply one — its source exists only in a build made with the rig. A test build can turn either uploader off:
 * [app] `false` makes the app's uploader withhold (it records, never creates); [extension] `false` makes
 * [extensionRegistrable] answer `false`, so the transitions deregister the extension (the extension cannot read
 * app memory, so off must be a deregistration).
 */
data class UploaderPin(val app: Boolean = true, val extension: Boolean = true)

/**
 * Whether the upload extension **may be registered** right now — the one fact left of the former mechanism
 * resolver (capability `upload-lifecycle`, "Whether the extension may be registered is one pure fact"; decision
 * record `changes/both-uploaders-active`, D4).
 *
 * Both uploaders run beside each other, so nothing here chooses which one runs: the app's uploader creates under
 * any usable grant, the extension under a full one, each deciding at its own entry gate. What the transitions
 * still need is whether the OS will accept a registration at all:
 *
 * - **Never below iOS 26.1** ([osSupportsOsDrivenUpload] `false`): the registration selector does not exist there,
 *   and calling it would trap and abort the process — so no pin can make this `true` there.
 * - **Only under [PermissionStatus.GRANTED]**: under `LIMITED` every registration write is refused (`3311`,
 *   measured SE2/26.6), and under no access there is nothing to register for.
 *
 * Total and pure, re-evaluated at every transition rather than resolved once per process: the grant changes at
 * runtime, and a stale answer would register over a refused grant.
 */
fun extensionRegistrable(
    osSupportsOsDrivenUpload: Boolean,
    permission: PermissionStatus,
    pin: UploaderPin? = null,
): Boolean = osSupportsOsDrivenUpload && permission == PermissionStatus.GRANTED && pin?.extension != false

/**
 * Which uploaders this OS carries, as the diagnostic dump names it (capability `diagnostic-logging`): the app's on
 * every version, and the extension beside it from iOS 26.1. A constant of the running build — not which one runs,
 * because both may.
 */
fun uploadersCarried(osSupportsOsDrivenUpload: Boolean): String =
    if (osSupportsOsDrivenUpload) "app+extension" else "app"

package app.snapsync.ports

/**
 * A development switch per uploader: a mechanism override is a runtime input a shipped build cannot carry. On a
 * production build it is **always** `null`: nothing in a production build can supply one — its source exists only in a
 * build made with the rig. A test build can turn either uploader off: [app] `false` makes the app's uploader withhold
 * (it records, never creates); [extension] `false` makes the OS uploader not registrable, so the transitions
 * deregister the extension (the extension cannot read app memory, so off must be a deregistration).
 */
data class UploaderPin(val app: Boolean = true, val extension: Boolean = true)

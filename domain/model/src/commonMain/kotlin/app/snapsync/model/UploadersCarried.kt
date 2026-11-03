package app.snapsync.model

/**
 * Which uploaders this OS carries, as the diagnostic dump names it: the app's on every version, and the extension
 * beside it from iOS 26.1. A constant of the running build — not which one runs, because both may.
 */
fun uploadersCarried(osSupportsOsDrivenUpload: Boolean): String =
    if (osSupportsOsDrivenUpload) "app+extension" else "app"

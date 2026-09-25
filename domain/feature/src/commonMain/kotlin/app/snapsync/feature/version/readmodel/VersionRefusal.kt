package app.snapsync.feature.version.readmodel

/**
 * A backend refusal of this build (capability `min-app-version`): the read-model `AppVersionGate` publishes and the
 * status host reduces into the update screen. [minimumVersion] is the version the backend named, or `null` when the
 * refusal carried none — which is still a refusal, never the same as no refusal at all.
 */
data class VersionRefusal(val minimumVersion: String?)

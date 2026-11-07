package app.snapsync.model

/**
 * The platform facts the shell knows and `:domain` cannot read for itself — supplied as a transcribed
 * value rather than a port, because every field is a constant of the running build.
 *
 * [uploadTier] names which uploaders this OS carries (`model/uploadersCarried`) — a constant of the build, never
 * which one runs: both may.
 *
 * [uploadBase] is the same baked host the boot banner names, and [reporterEnvironment] distinguishes
 * a deliberately DSN-injected dev build from a production one — without it, a dump from the
 * on-device verification path is indistinguishable from a real user's.
 */
class DiagnosticEnvironment(
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val deviceModel: String,
    val uploadTier: String,
    val uploadBase: String,
    val reporterEnvironment: String,
) {
    companion object {
        /** Off-device compositions (world, harnesses, tests) know none of these. */
        val UNKNOWN: DiagnosticEnvironment = DiagnosticEnvironment(
            appVersion = "unknown",
            buildNumber = "unknown",
            osVersion = "unknown",
            deviceModel = "unknown",
            uploadTier = "unknown",
            uploadBase = "unknown",
            reporterEnvironment = "unknown",
        )
    }
}

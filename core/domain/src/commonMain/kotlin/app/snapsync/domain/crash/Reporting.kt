package app.snapsync.domain.crash

import app.snapsync.domain.report.Platform
import app.snapsync.domain.report.ReportDestination

/**
 * How the channel starts: where it reports, what the build is, and how many breadcrumbs an event carries. Every value
 * is a fact of the build ([ReportingBuild.reporting]) — the adapter reads nothing of the platform itself.
 */
class CrashOptions(
    val dsn: String,
    /** The build's version line, or `null` for none: an empty release is worse than none, it looks real. */
    val release: String? = null,
    /** Which deployment the build belongs to (`production`, `development`). */
    val environment: String? = null,
    /** The build number to stamp on every event, or `null` to leave the SDK's own ([ReportingBuild.reporting]). */
    val dist: String? = null,
    /** Tags every event of this process carries, on the channel's global scope ([CrashTags.PLATFORM], `PROCESS`). */
    val tags: Map<String, String> = emptyMap(),
    /** Pinned, not defaulted: it is a row of the whole-event sum (`DiagnosticDump.LOG_BUDGET_BYTES`). */
    val maxBreadcrumbs: Int = EventBounds.MAX_BREADCRUMBS,
)

/**
 * Whether this build reports, and how its channel starts: the build's DSN is `null` on every dev, sideload, emulator
 * and simulator build, and then nothing starts, nothing is sent, and no connection is ever opened to the reporting
 * host.
 */
sealed interface Reporting {
    /** Where a bug report goes on this build: sent where it reports, else kept on the device. */
    val destination: ReportDestination

    /** A distributed build: its channel starts with [options]. */
    class To(val options: CrashOptions) : Reporting {
        override val destination: ReportDestination get() = ReportDestination.DEVELOPER
    }

    /** A build that reports nowhere. */
    data object Nowhere : Reporting {
        override val destination: ReportDestination get() = ReportDestination.THIS_DEVICE
    }
}

/**
 * The facts of the running build that decide how it reports — every one a constant of the build, read from its
 * `BuildInfo` once.
 */
class ReportingBuild(
    /** Where the build reports, `null` for nowhere. */
    private val dsn: String?,
    /** Its version line; a blank one names no release. */
    private val version: String,
    private val environment: String,
    private val platform: Platform,
    private val buildNumber: String,
    /** Its process's own id; none, or a blank one (a test executable), carries no process tag. */
    private val processId: String?,
) {
    /**
     * How this build reports. Its build number is stamped only where the SDK keeps the one an event was captured under:
     * - **iOS: unset, on purpose.** sentry-cocoa applies the dist option UNCONDITIONALLY at send time, and a crash is
     *   delivered on a LATER launch — possibly after the device updated — so a dist set here would overwrite the build
     *   number the crash report recorded when it crashed, and symbols would resolve against another build.
     * - **Android: the build number.** sentry-java applies options at capture, before an event is cached, so a cached
     *   event keeps the build it was captured under (`CrashReporterContract`'s `RESTART_CACHED_EVENT_KEEPS_ITS_BUILD`).
     */
    fun reporting(): Reporting = dsn?.let { Reporting.To(options(it)) } ?: Reporting.Nowhere

    private fun options(dsn: String) = CrashOptions(
        dsn = dsn,
        release = version.takeIf(String::isNotBlank),
        environment = environment,
        dist = buildNumber.takeIf { platform == Platform.ANDROID && it.isNotBlank() },
        tags = mapOf(CrashTags.PLATFORM to platform.tag) +
            listOfNotNull(processId?.takeIf(String::isNotBlank)?.let { CrashTags.PROCESS to it }),
    )
}

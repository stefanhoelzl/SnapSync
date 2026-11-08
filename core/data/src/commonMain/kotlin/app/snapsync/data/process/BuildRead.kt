package app.snapsync.data.process

import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.version.Store
import app.snapsync.ports.BuildInfo

/** The running build's facts the screen states, read when asked. */
class BuildRead(private val build: BuildInfo) {
    /** This build's store page, or `null` when it carries none. */
    fun store(): Store? = build.store

    /** Which build this is, as the app menu names it. */
    fun label(): BuildLabel = BuildLabel(version = build.appVersion, buildNumber = build.buildNumber)
}

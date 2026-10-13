package app.snapsync.data.backend

import app.snapsync.ports.BuildInfo

/**
 * Where requests go and which version they declare — both read per request, so a build whose declared version changes
 * at runtime (a test playing an update) is answered as the version it declares now.
 */
interface BackendTarget {
    /** The device API's base, one version prefix included. */
    val base: String

    /** The marketing version every request declares; blank declares none. */
    val declaredVersion: String
}

/** The running build's own backend and version — the [BuildInfo] port's facts. */
class BuildTarget(private val build: BuildInfo) : BackendTarget {
    override val base: String get() = build.uploadHost

    override val declaredVersion: String get() = build.appVersion
}

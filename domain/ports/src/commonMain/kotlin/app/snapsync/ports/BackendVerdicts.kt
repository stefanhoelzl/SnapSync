package app.snapsync.ports

/**
 * What the backend's answers tell the core — an **inbound** port (`docs/architecture.md`, "OS entry points
 * cross an inbound port"): the core implements it, and the credential-carrying HTTP interceptor drives it.
 *
 * It is one object rather than three callbacks because the three are one wiring. A root that assembled them one
 * by one could wire two and forget the third, and nothing would say so: a forgotten version refusal leaves an
 * obsolete build silently failing every call, and a forgotten rejection keeps sending a dead credential.
 * Handing the interceptor the object the core exposes leaves nothing to assemble.
 */
interface BackendVerdicts {
    /** The backend rejected [the token a request carried][sentToken] on a gated route. */
    suspend fun credentialRejected(sentToken: String)

    /** The backend refuses this build as too old, naming the minimum it accepts when it says one. */
    fun versionRefused(minimumVersion: String?)

    /** A request was served — which clears a refusal once the build is new enough. */
    fun served()
}

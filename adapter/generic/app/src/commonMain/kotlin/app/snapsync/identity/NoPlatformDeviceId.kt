package app.snapsync.identity

import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.ports.PlatformDeviceId

/**
 * The [PlatformDeviceId] of a platform that offers no stable id this app may use — iOS (the vendor id changes when
 * every app of the vendor is removed, and the advertising id is not ours to take) and the JVM. The identity service
 * then mints a random UUID, exactly as it always has.
 */
@NeverAnswers(
    Never(member = "stableId", answer = "returns"),
)
class NoPlatformDeviceId : PlatformDeviceId {
    override fun stableId(): String? = null
}

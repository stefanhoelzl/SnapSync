package app.snapsync.services.trust

import app.snapsync.model.DeviceRefusal
import app.snapsync.ports.AttestationChain

/**
 * What the app knows about the latest refused attestation, for a report the user sends from "Report this": the
 * service's [reason] and diagnostic [detail] as it named them, and the [chain] the phone presented, where the platform
 * exposes it. Held in memory only.
 */
data class RefusalFacts(val reason: DeviceRefusal, val detail: String?, val chain: PresentedChain?)

/**
 * The certificates a refused proof presented, as trust hands them on — the integrity port's [AttestationChain],
 * translated once where the refusal is recorded, so a reader of the refusal never names the port's vocabulary.
 */
data class PresentedChain(val certificates: List<PresentedCertificate>, val rootKeySha256: String) {
    companion object {
        fun of(chain: AttestationChain): PresentedChain = PresentedChain(
            chain.certificates.map { PresentedCertificate(it.subject, it.issuer, it.notBefore, it.notAfter, it.key) },
            chain.rootKeySha256,
        )
    }
}

/** One certificate of a [PresentedChain]: names as RFC 2253, validity as ISO instants, key e.g. `EC 256`. */
data class PresentedCertificate(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val key: String,
)

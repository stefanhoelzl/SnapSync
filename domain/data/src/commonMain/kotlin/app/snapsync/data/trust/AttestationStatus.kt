package app.snapsync.data.trust

import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.AttestationHealthVerdict
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.ports.AttestationChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.jvm.JvmInline

/**
 * What the app's credential has learnt, as the screen and the reports read it — read-model cells the attesting
 * credential writes, derived and never authority (the token in the store is): a relaunch rebuilds them from its first
 * refresh.
 *
 * [attested] and [refusal] are **bracketed per refresh**: cleared when one begins, set when it ends, so an outcome from
 * an earlier wake is never the first frame of a later foreground (`SNAPSYNC-20`; decision record
 * `2026-08-25-correct-attestation-health-surfacing`). The refusal's facts are not: a report opened while a refresh runs
 * must still find them.
 *
 * Only the refresh that began LAST may publish: refreshes overlap (a wake's own, and a rejected call's recovery), and
 * one that judged a token fresh before a rejection dropped it would otherwise overwrite the refusal the recovery
 * found. An outdated verdict is discarded; the later refresh publishes its own.
 */
@OptIn(ExperimentalAtomicApi::class)
class AttestationStatus {
    private val attestedCell = MutableStateFlow(true)
    private val refusalCell = MutableStateFlow<DeviceRefusal?>(null)
    private val faultCell = MutableStateFlow<ServiceFault?>(null)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val latest = AtomicLong(0)

    @Volatile
    private var facts: RefusalFacts? = null

    /**
     * Whether this device holds a usable token: `false` only when the token is unusable AND the latest refresh could
     * not obtain one ([AttestationHealthVerdict]). The one fact the status screen surfaces.
     */
    val attested: StateFlow<Boolean> = attestedCell.asStateFlow()

    /** Why the service refused this phone at the latest refresh — `null` when it did not, or gave no answer. */
    val refusal: StateFlow<DeviceRefusal?> = refusalCell.asStateFlow()

    /**
     * How the service failed the latest refresh — `null` when it answered, or none was asked: a member whose proof
     * could not be renewed because SnapSync failed is told SnapSync's condition, never that this device cannot be
     * verified (decision record `changes/tell-service-faults`).
     */
    val fault: StateFlow<ServiceFault?> = faultCell.asStateFlow()

    /**
     * Emits whenever a NEW token is obtained — a first mint, a re-attestation and every renewal alike. What must be
     * re-sent with a new credential listens: the push registration.
     */
    val tokenChanged: Flow<Unit> = changes

    /** The latest refused attestation's facts, for a report sent from "Report this", or `null` once one succeeded. */
    fun refusalFacts(): RefusalFacts? = facts

    /** A refresh begins: the previous verdict is gone, and this refresh's [RefreshTicket] may settle. */
    internal fun attempting(): RefreshTicket {
        attestedCell.value = true
        refusalCell.value = null
        faultCell.value = null
        return RefreshTicket(latest.incrementAndFetch())
    }

    /** The refresh holding [ticket] ended — published only when no refresh began after it. */
    internal fun settled(
        ticket: RefreshTicket,
        health: AttestationHealthVerdict,
        refusal: DeviceRefusal?,
        fault: ServiceFault?,
    ) =
        when (latest.load() == ticket.number) {
            true -> {
                attestedCell.value = health == AttestationHealthVerdict.Attested
                refusalCell.value = refusal
                faultCell.value = fault
            }
            false -> Unit
        }

    internal fun refused(reason: DeviceRefusal, detail: String?, chain: PresentedChain?) {
        facts = RefusalFacts(reason, detail, chain)
    }

    internal fun accepted() {
        facts = null
        changes.tryEmit(Unit)
    }
}

/** Which refresh is publishing: the order refreshes began in. */
@JvmInline
internal value class RefreshTicket(val number: Long)

/**
 * What the app knows about the latest refused attestation, for a report the user sends from "Report this": the
 * service's [reason] and diagnostic [detail] as it named them, and the [chain] the phone presented, where the platform
 * exposes it. Held in memory only.
 */
data class RefusalFacts(val reason: DeviceRefusal, val detail: String?, val chain: PresentedChain?)

/**
 * The certificates a refused proof presented — the integrity port's [AttestationChain], translated once where the
 * refusal is recorded, so a reader of the refusal never names the port's vocabulary.
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

package app.snapsync.data.identity

import app.snapsync.data.trust.TrustSlots
import app.snapsync.domain.device.DeviceId
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.WriteOutcome
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * **This install's device id**: the byte partition, the manifest key, and the identity every backend call is made
 * under — kept in the `SecureStore`'s shared device-id slot, so the app and the upload extension read **one** item.
 *
 * Two kinds, and only the app's may create one: [StoredDeviceIdentity] (the extension's) reads the slot and nothing
 * else; [MintingDeviceIdentity] (the app's) mints when the slot is genuinely empty. The first id resolved is kept for
 * the process; a failure is never kept, so a resolve attempted while the device is locked is retried on the next call.
 */
interface DeviceIdentity {
    fun id(): DeviceIdRead
}

/** What resolving the device id found. */
sealed interface DeviceIdRead {
    data class Ok(val value: DeviceId, val via: Via) : DeviceIdRead

    /** The slot holds no id, and this process may not mint one — or a reader asked never to mint. */
    data object Absent : DeviceIdRead

    /**
     * The store could not be read or written, or holds something that is not a device id. Never mistaken for absence.
     */
    data class Unavailable(val detail: String) : DeviceIdRead

    enum class Via { READ, MINTED }
}

/**
 * One resolution per process, SERIALISED — `lazy`'s synchronized initialisation keeps two first calls from both minting
 * (each would read absent, mint a different id and write it) — and kept only when it found an id: a failed one is
 * replaced by a fresh attempt for the next call.
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class ResolvedOnce internal constructor() : DeviceIdentity {
    private val attempt = AtomicReference(lazy(::resolveOnce))

    override fun id(): DeviceIdRead {
        val current = attempt.load()
        return current.value.also { retryAfter(current, it) }
    }

    /** The resolution this process holds, if it has one — never resolving to get one. */
    protected fun kept(): DeviceIdRead? = attempt.load().takeIf { it.isInitialized() }?.value as? DeviceIdRead.Ok

    protected abstract fun resolveOnce(): DeviceIdRead

    private fun retryAfter(current: Lazy<DeviceIdRead>, read: DeviceIdRead) = when (read) {
        is DeviceIdRead.Ok -> true
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> attempt.compareAndSet(current, lazy(::resolveOnce))
    }
}

/** The device-id slot as it stands: the id it holds, none, or why it could not be read. */
internal fun stored(store: SecureStore, via: DeviceIdRead.Via): DeviceIdRead =
    when (val read = store.read(TrustSlots.DEVICE_ID)) {
        is SecureStoreRead.Found -> spelled(read.value, via, "the stored device id is not a device id")
        SecureStoreRead.Absent -> DeviceIdRead.Absent
        is SecureStoreRead.Unavailable -> DeviceIdRead.Unavailable(read.detail)
    }

/**
 * [text] as the device id it spells — or, when it spells none, an identity no backend call could be made under, so it
 * is reported as unusable ([otherwise]) rather than handed out.
 */
private fun spelled(text: String, via: DeviceIdRead.Via, otherwise: String): DeviceIdRead =
    DeviceId.parse(text)?.let { DeviceIdRead.Ok(it, via) } ?: DeviceIdRead.Unavailable(otherwise)

/** The upload extension's identity: the shared slot, read; absence is [DeviceIdRead.Absent], never minted over. */
class StoredDeviceIdentity(private val store: SecureStore) : ResolvedOnce() {
    override fun resolveOnce(): DeviceIdRead = stored(store, DeviceIdRead.Via.READ).also(::logged)
}

/**
 * The app's identity: the shared slot → mint → persist. A mint is the platform's own stable id where it offers one
 * ([PlatformDeviceId]), else a random UUID in the platform's canonical upper-case form. "Could not look" never mints,
 * and a refused write fails the resolution — an id is never handed out unsaved. Only the app process constructs one.
 */
class MintingDeviceIdentity @AppProcessOnly constructor(
    private val store: SecureStore,
    private val platformDeviceId: PlatformDeviceId,
) : ResolvedOnce() {

    /**
     * The id this install already has, **never minting or writing one** — for a reader that must not create an
     * identity as a side effect (a bug report): this process's resolution when it has one, else the slot as it stands.
     */
    fun current(): DeviceIdRead = kept() ?: stored(store, DeviceIdRead.Via.READ)

    override fun resolveOnce(): DeviceIdRead = when (val read = stored(store, DeviceIdRead.Via.READ)) {
        is DeviceIdRead.Ok, is DeviceIdRead.Unavailable -> read
        DeviceIdRead.Absent -> minted()
    }.also(::logged)

    @OptIn(ExperimentalUuidApi::class)
    private fun minted(): DeviceIdRead {
        val id = platformDeviceId.stableId() ?: Uuid.random().toString().uppercase()
        return when (val written = store.write(TrustSlots.DEVICE_ID, id)) {
            WriteOutcome.Ok -> spelled(id, DeviceIdRead.Via.MINTED, "the minted device id is not a device id")
            is WriteOutcome.Failed -> DeviceIdRead.Unavailable("write refused: ${written.detail}")
        }
    }
}

/**
 * Each process logs the id it resolved — the two processes are supposed to print the same id, and when they did not,
 * for nine hours in July, only this line would have said so.
 */
private fun logged(read: DeviceIdRead) = when (read) {
    is DeviceIdRead.Ok -> LOG.unlabelled.i("device identity", "id" of read.value, "via" of read.via)
    DeviceIdRead.Absent -> LOG.unlabelled.i("device identity absent, and this process may not mint")
    is DeviceIdRead.Unavailable -> Unit // the caller's skip line names it
}

private val LOG = Log("DeviceIdentity")

package app.snapsync.services.identity

import app.snapsync.model.DeviceIdResult
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreResolution
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureStore
import app.snapsync.services.secure.readExisting
import app.snapsync.services.secure.resolveOrMint
import co.touchlab.kermit.Logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * **This install's device id** (capability `photo-sharing`): the `/files/devices/<deviceId>/` byte partition, the
 * manifest key, and the identity every backend call is made under — kept in the [SecureStore]'s shared device-id
 * slot, so the app and the upload extension read **one** item.
 *
 * The order is normative (`resolveOrMint`): the shared slot → mint → persist. A mint is the platform's own stable
 * id where it offers one ([PlatformDeviceId]), else a random UUID. "Could not look" never mints, and a refused
 * persisting write fails the resolution — an id is never handed out unsaved.
 *
 * **Two roles, and only one may create an identity** ([DeviceIdentityRole]): the extension reads the shared slot
 * and nothing else; absence there is [DeviceIdResult.AbsentNotMintable].
 *
 * The first successful resolution is kept for the process; a failure is never kept, so a resolve attempted while
 * the device is locked is retried on the next call. The resolution is logged once per process in both roles —
 * the two processes are supposed to print the same id, and when they did not, for nine hours in July, only this
 * line would have said so.
 */
class PersistedDeviceIdentity(
    private val role: DeviceIdentityRole,
    private val store: SecureStore,
    private val platformDeviceId: PlatformDeviceId,
    private val log: Logger = Logger.withTag("deviceIdentity"),
) {

    /**
     * The one resolution of this process, SERIALISED: `lazy`'s synchronized mode is what keeps two first calls from
     * both minting (each would read absent, mint a different id and write it — and the loser would hand out an id
     * the store no longer holds). A lazy whose initializer throws is retried on the next access, so only a success
     * is kept (`DeviceIdentityRetryTest` pins that stdlib property).
     */
    private val resolution: Lazy<DeviceIdResult.Id> = lazy {
        when (val result = resolveOnce()) {
            is DeviceIdResult.Id -> result
            else -> throw NotResolved(result)
        }
    }
    private val resolved: DeviceIdResult.Id by resolution

    /** A resolution that did not produce an id — thrown out of the lazy so it is not kept, caught in [resolve]. */
    private class NotResolved(val result: DeviceIdResult) : RuntimeException()

    /** The id, or why there is none. Never throws for a store that answered; only a success is kept. */
    fun resolve(): DeviceIdResult = try {
        resolved
    } catch (failure: NotResolved) {
        failure.result
    }

    /**
     * The id this install already has, **never minting or writing one** — for a reader that must not create an
     * identity as a side effect: a bug report (capability `privacy-security`). This process's resolution when it has
     * one; otherwise the shared slot as it stands.
     * [DeviceIdResult.AbsentNotMintable] here means only "none stored yet", whatever this process's role.
     */
    fun current(): DeviceIdResult {
        if (resolution.isInitialized()) return resolution.value
        // The store's raw read: an unreadable slot answers Unavailable here rather than throwing.
        return when (val read = store.read(SecureSlots.DEVICE_ID)) {
            is SecureStoreRead.Found -> DeviceIdResult.Id(read.value, DeviceIdResult.Via.READ)
            SecureStoreRead.Absent -> DeviceIdResult.AbsentNotMintable
            is SecureStoreRead.Unavailable -> DeviceIdResult.Unavailable(read.detail)
        }
    }

    private fun resolveOnce(): DeviceIdResult {
        var resolution: SecureStoreResolution? = null
        val result = try {
            when (role) {
                // Asks the shared slot and accepts its answer; absence is never minted over here.
                DeviceIdentityRole.READ_ONLY ->
                    readExisting(store, SecureSlots.DEVICE_ID, onResolution = { resolution = it })
                        ?.let { DeviceIdResult.Id(it, DeviceIdResult.Via.READ) }
                        ?: DeviceIdResult.AbsentNotMintable

                DeviceIdentityRole.MINTING -> resolveOrMint(
                    store,
                    SecureSlots.DEVICE_ID,
                    onResolution = { resolution = it },
                    generate = ::mint,
                ).let { DeviceIdResult.Id(it, via(resolution)) }
            }
        } catch (unavailable: SecureStoreUnavailable) {
            DeviceIdResult.Unavailable(unavailable.detail)
        }
        when (result) {
            is DeviceIdResult.Id -> log.i { "device identity: id=${result.value} via=${resolution.describe()}" }
            DeviceIdResult.AbsentNotMintable ->
                log.i { "device identity: absent in the shared group and this process may not mint" }
            is DeviceIdResult.Unavailable -> Unit // the caller's skip line names it
        }
        return result
    }

    /** The transitional throwing form every consumer reads today (`PersistedDeviceIdentity`). */
    fun deviceId(): String = when (val result = resolve()) {
        is DeviceIdResult.Id -> result.value
        is DeviceIdResult.Unavailable -> throw SecureStoreUnavailable(result.detail)
        DeviceIdResult.AbsentNotMintable -> throw DeviceIdentityAbsent()
    }

    /**
     * The platform's stable id where it offers one; otherwise a random UUID in the platform's canonical upper-case
     * form (the form every id minted before this service has — `NSUUID().UUIDString`).
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun mint(): String = platformDeviceId.stableId() ?: Uuid.random().toString().uppercase()

    private fun via(resolution: SecureStoreResolution?): DeviceIdResult.Via = when (resolution) {
        SecureStoreResolution.Minted -> DeviceIdResult.Via.MINTED
        is SecureStoreResolution.Found, null -> DeviceIdResult.Via.READ
    }

    private fun SecureStoreResolution?.describe(): String = when (this) {
        is SecureStoreResolution.Found -> "read(protection=$protection)"
        SecureStoreResolution.Minted -> "minted"
        null -> "unreported"
    }
}

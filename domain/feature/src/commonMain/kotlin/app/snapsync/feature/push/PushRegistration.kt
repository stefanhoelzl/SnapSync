package app.snapsync.feature.push

import app.snapsync.model.ApnsPushToken
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.backend.PushTokenPublisher
import app.snapsync.services.push.PushTokenSource

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Registers the device's APNs token with the backend (capability `receiving-photos`, "Registration timing —
 * launch, join, and rotation"). It publishes through the [publisher] port, whose adapter owns the address and the
 * body. **No event id** (the token is device-scoped, event-independent). A failed publish is absorbed (logged), so
 * registration never blocks join/upload/download.
 *
 * **Change-driven.** The app asks the OS for the token at every app entry, and the OS answers every time — mostly
 * with the token it gave last time. A delivered token is therefore published only when the (`token`, `env`,
 * `deviceId`) triple it would register differs from the last one the backend accepted, which [record] keeps; and
 * the triple is recorded only after the backend accepted it, so a failed publish is re-sent at the next entry.
 * Publishing at every launch instead was measured on 102 of 108 process starts, 0.5–3 s each, most of them
 * background wakes (decision record `changes/own-work-per-wake`, D12).
 *
 * Two triggers publish **whatever the record holds** — [register]: a join, and a fresh device credential (a
 * mint, a re-attestation, a periodic renewal alike). They are what heals a registration the backend lost or
 * refused, so they must not be suppressed by a record that believes otherwise. The `PUT` is idempotent
 * (last-write-wins), which is what makes a redundant one safe — never a reason to send one.
 */
class PushRegistration(
    private val publisher: PushTokenPublisher,
    private val record: PushRegistrationRecord,
    /** Whose registration this is: part of the triple, since a changed device id is a registration the backend
     *  has never seen. Read per publish — it is a Keychain read that throws while protected data is unavailable. */
    private val identity: PersistedDeviceIdentity,
    private val log: Logger = Logger.withTag("PushRegistration"),
) {
    /**
     * Publish [token] now, whatever [record] holds — the join and fresh-credential triggers — and record it on
     * success. Absorbs any failure (never throws to the caller) — a throw from the publisher included, though its
     * contract forbids one: a throw here would end [run]'s collector for the rest of the process, and no later token
     * or credential change would be registered (B11: an unreadable device identity did exactly that). Cancellation
     * still propagates.
     */
    suspend fun register(token: ApnsPushToken) {
        publish(token, keyOf(token))
    }

    /**
     * Publish [token] only when its triple differs from the last one the backend accepted — the trigger of every
     * OS delivery. An unreadable device identity publishes nothing: the publisher could not address the device
     * either, and the next entry's delivery asks again.
     */
    suspend fun registerIfChanged(token: ApnsPushToken) {
        val key = keyOf(token) ?: return
        if (key == runCatchingCancellable { record.loadLastRegistered() }.getOrNull()) {
            log.i { "push token unchanged since the last accepted registration — not re-published" }
            return
        }
        publish(token, key)
    }

    /**
     * Compare-and-publish on every OS delivery, and publish unconditionally whenever [credentialChanged] fires.
     * Suspends for the caller scope's lifetime (installed once per process from the composition).
     *
     * **[credentialChanged] is not an optimization; without it a refused registration waits for a change.** A `PUT`
     * refused because the device had no valid attestation yet (a fresh install races attestation, or the backend
     * collected its record) leaves the triple unrecorded, so the next app entry would re-send it — but a device
     * that receives no silent pushes gets few entries. A new credential is exactly what makes the refused `PUT`
     * acceptable, so it re-sends at once; a periodic renewal re-sends too, deliberately without telling a renewal
     * from a mint (it costs one publish per renewal, and keeps the one healing path unconditional).
     */
    suspend fun run(source: PushTokenSource, credentialChanged: Flow<Unit> = emptyFlow()) {
        merge(
            source.deliveries.map { Trigger.DELIVERY },
            credentialChanged.map { Trigger.CREDENTIAL },
        ).collect { trigger ->
            val token = ApnsPushToken(source.token.value ?: return@collect, source.env)
            when (trigger) {
                Trigger.DELIVERY -> registerIfChanged(token)
                Trigger.CREDENTIAL -> register(token)
            }
        }
    }

    private suspend fun publish(token: ApnsPushToken, key: String?) {
        runCatchingCancellable { publisher.publish(token) }.getOrElse { Result.failure(it) }
            .onSuccess {
                key?.let { runCatchingCancellable { record.saveLastRegistered(it) } }
                log.i { "push token registered" }
            }
            .onFailure { log.w(it) { "push registration failed (re-sent at the next entry or credential)" } }
    }

    /** The registration [token] would make, or `null` while the device identity cannot be read (logged). */
    private fun keyOf(token: ApnsPushToken): String? =
        runCatchingCancellable { registrationKey(token, identity.deviceId()) }
            .onFailure { log.w(it) { "device identity unreadable — push registration deferred to the next trigger" } }
            .getOrNull()

    private enum class Trigger { DELIVERY, CREDENTIAL }
}

/**
 * The one string a registration is recorded as: the three facts the backend's registration depends on. Newline-
 * separated because none of them can contain one (a hex token, `sandbox`/`production`, a UUID).
 */
internal fun registrationKey(token: ApnsPushToken, deviceId: String): String =
    listOf(token.env, deviceId, token.token).joinToString("\n")

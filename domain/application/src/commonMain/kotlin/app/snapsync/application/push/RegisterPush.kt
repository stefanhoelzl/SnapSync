package app.snapsync.application.push

import app.snapsync.application.EntryPointCall
import app.snapsync.data.push.PushEndpointPublisher
import app.snapsync.data.push.PushPublishReply
import app.snapsync.data.push.PushRegistrationRecord
import app.snapsync.data.push.PushTokens
import app.snapsync.data.push.RegistrationRead
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.push.PushRegistrationKey
import app.snapsync.domain.push.PushRegistrationVerdict
import app.snapsync.domain.push.PushTrigger
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("RegisterPush")

/**
 * Register this device's push token with the backend, so it can be woken for new photos — at every OS delivery of the
 * token, at a new device credential and at a join ([PushRegistrationVerdict]). A registration is recorded only once the
 * backend accepted it, so one that failed is re-sent at the next trigger; nothing here ever blocks a join, an upload or
 * a download.
 *
 * Triggers may run at the same time — a delivery beside a credential change. Each publishes what it read, so the worst
 * a race costs is one more idempotent `PUT` at the next entry: a record that ends up older than the backend's word
 * reads as changed.
 */
class RegisterPush(
    private val tokens: PushTokens,
    private val record: PushRegistrationRecord,
    private val publisher: PushEndpointPublisher,
) {
    @EntryPointCall
    suspend operator fun invoke(trigger: PushTrigger): PushRegistrationOutcome = when (val read = tokens.current()) {
        is RegistrationRead.Held -> held(trigger, read)
        RegistrationRead.NoToken -> PushRegistrationOutcome.NoToken
        RegistrationRead.NoIdentity -> deferred()
    }

    private suspend fun held(trigger: PushTrigger, held: RegistrationRead.Held): PushRegistrationOutcome =
        when (PushRegistrationVerdict.of(trigger, held.key, record.lastAccepted())) {
            PushRegistrationVerdict.PUBLISH -> published(held.key, publisher.publish(held.endpoint))
            PushRegistrationVerdict.UNCHANGED -> PushRegistrationOutcome.Unchanged.also {
                log.i("push token unchanged since the last accepted registration — not re-published")
            }
        }

    private suspend fun published(key: PushRegistrationKey, reply: PushPublishReply): PushRegistrationOutcome =
        when (reply) {
            PushPublishReply.Accepted -> recorded(record.save(key))
            is PushPublishReply.Refused -> PushRegistrationOutcome.NotRegistered.also {
                log.w("push registration failed (re-sent at the next entry or credential)", "reply" of reply.detail)
            }
            PushPublishReply.NoIdentity -> deferred()
        }

    private suspend fun recorded(saved: StoreWrite): PushRegistrationOutcome = PushRegistrationOutcome.Registered.also {
        when (saved) {
            StoreWrite.Written -> log.i("push token registered")
            is StoreWrite.Failed -> log.w(
                "push token registered, but not recorded — the next entry publishes again",
                "detail" of saved.detail,
            )
        }
    }

    private suspend fun deferred(): PushRegistrationOutcome = PushRegistrationOutcome.Deferred.also {
        log.w("device identity unreadable — push registration deferred to the next trigger")
    }
}

/** What a registration came to. */
sealed interface PushRegistrationOutcome {
    /** The backend accepted it. */
    data object Registered : PushRegistrationOutcome

    /** Delivered again unchanged: nothing was sent. */
    data object Unchanged : PushRegistrationOutcome

    /** No token has been delivered yet: nothing to register. */
    data object NoToken : PushRegistrationOutcome

    /** The device id cannot be read now: the next trigger tries again. */
    data object Deferred : PushRegistrationOutcome

    /** The backend did not take it: the next entry or credential re-sends it. */
    data object NotRegistered : PushRegistrationOutcome
}

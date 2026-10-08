package app.snapsync.contracts

import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceManifest
import app.snapsync.model.MintRequest
import app.snapsync.model.ProofFormat
import app.snapsync.model.PushEndpoint
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.Backend
import kotlin.test.assertIs

/**
 * What every route answers when no backend answers at all: the request did not complete, which is neither a refusal
 * nor a body. Part of [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.transportClauses() {
    clause(
        "NO_BACKEND_EVERY_ROUTE_IS_UNREACHABLE",
        BackendState.NO_BACKEND,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).with(Reply.Unreachable::class)
                answers(Backend::mintToken).with(Reply.Unreachable::class)
                answers(Backend::renewToken).with(Reply.Unreachable::class)
                answers(Backend::createEvent).with(Reply.Unreachable::class)
                answers(Backend::getEvent).with(Reply.Unreachable::class)
                answers(Backend::renameEvent).with(Reply.Unreachable::class)
                answers(Backend::joinEvent).with(Reply.Unreachable::class)
                answers(Backend::publishManifest).with(Reply.Unreachable::class)
                answers(Backend::leaveEvent).with(Reply.Unreachable::class)
                answers(Backend::eventFiles).with(Reply.Unreachable::class)
                answers(Backend::deviceFiles).with(Reply.Unreachable::class)
                answers(Backend::putDeviceConfig).with(Reply.Unreachable::class)
            }
        },
    ) { s ->
        everyRoute(s).forEach { (route, reply) ->
            assertIs<Reply.Unreachable>(reply, "$route: nothing answered, so nothing was refused or read")
        }
    }

    clause(
        "UNREADABLE_SUCCESS_EVERY_READ_IS_MALFORMED",
        BackendState.UNREADABLE_SUCCESS,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).with(Reply.Malformed::class)
                answers(Backend::mintToken).with(Reply.Malformed::class)
                answers(Backend::renewToken).with(Reply.Malformed::class)
                answers(Backend::createEvent).with(Reply.Malformed::class)
                answers(Backend::getEvent).with(Reply.Malformed::class)
                answers(Backend::renameEvent).with(Reply.Malformed::class)
                answers(Backend::eventFiles).with(Reply.Malformed::class)
                answers(Backend::deviceFiles).with(Reply.Malformed::class)
                answers(Backend::joinEvent).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        val read = setOf(
            "challenge",
            "mintToken",
            "renewToken",
            "createEvent",
            "getEvent",
            "renameEvent",
            "eventFiles",
            "deviceFiles",
        )
        everyRoute(s).forEach { (route, reply) ->
            if (route in read) {
                assertIs<Reply.Malformed>(reply, "$route: a success whose body this build cannot read is never a value")
            } else {
                assertIs<Reply.Ok<*>>(reply, "$route: a body-less route is served by any success")
            }
        }
    }
}

/** One call of every route, named — each with inputs any backend would accept, so only the transport can fail it. */
private suspend fun everyRoute(s: EdgeSubject<Backend>): List<Pair<String, Reply<*>>> {
    val event = s.seeded.eventId
    val device = s.seeded.deviceId
    val challenge = "a-challenge"
    return listOf(
        "challenge" to s.port.challenge(),
        "mintToken" to s.port.mintToken(
            MintRequest(device, KEY_ID, ProofFormat.APP_ATTEST, "attestation".encodeToByteArray(), challenge),
        ),
        "renewToken" to s.port.renewToken(RenewRequest(device, "assertion".encodeToByteArray(), challenge)),
        "createEvent" to s.port.createEvent(s.token, CreateEventRequest("Unanswered", SEEDED_STARTS_AT, SEEDED_ENDS_AT)),
        "getEvent" to s.port.getEvent(s.token, event),
        "renameEvent" to s.port.renameEvent(s.token, event, "Unanswered"),
        "joinEvent" to s.port.joinEvent(s.token, event, device),
        "publishManifest" to s.port.publishManifest(s.token, event, device, DeviceManifest(device, emptyList(), 0)),
        "leaveEvent" to s.port.leaveEvent(s.token, event, device, received = false),
        "eventFiles" to s.port.eventFiles(s.token, event, null, UnionTrigger.FOREGROUND),
        "deviceFiles" to s.port.deviceFiles(s.token, event, device),
        "putDeviceConfig" to s.port.putDeviceConfig(s.token, device, PushEndpoint("apns", "a-token", "development")),
    )
}

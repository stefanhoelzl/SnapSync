package app.snapsync.contracts

import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerError
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.MintRequest
import app.snapsync.wire.models.PushRegistration
import app.snapsync.wire.models.RenewRequest
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What every operation answers when no backend answers at all — the request did not complete, which is neither a
 * refusal nor a body — when an answer is one this build cannot read, and when the server fails or is in a migration
 * window. Part of [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.transportClauses() {
    clause(
        "NO_BACKEND_EVERY_OPERATION_IS_UNREACHABLE",
        BackendState.NO_BACKEND,
        covers = cells { backend(Routes.Challenge.ID, Unreachable::class) },
    ) { s ->
        everyOperation(s).forEach { (operation, reply) ->
            assertIs<Unreachable>(reply, "$operation: nothing answered, so nothing was refused or read")
        }
    }

    clause(
        "UNREADABLE_SUCCESS_EVERY_READ_IS_MALFORMED",
        BackendState.UNREADABLE_SUCCESS,
        covers = cells { backend(Routes.Challenge.ID, Malformed::class) },
    ) { s ->
        everyOperation(s).forEach { (operation, reply) ->
            // A success that declares no body is the backend's only with none: a body there came from something in
            // its place — a Wi-Fi login page, a proxy — and is never a done write (`changes/tell-service-faults`, D2).
            assertIs<Malformed>(
                reply,
                "$operation: an answer this build cannot read is never a value, nor a done write",
            )
        }
    }

    clause(
        "SERVER_FAILING_EVERY_OPERATION_IS_A_SERVER_ERROR",
        BackendState.SERVER_FAILING,
        covers = cells { backend(Routes.Challenge.ID, ServerError::class) },
    ) { s ->
        everyOperation(s).forEach { (operation, reply) ->
            assertIs<ServerError>(reply, "$operation: a failing store is never absence, nor a refusal")
        }
    }

    clause(
        "IN_MAINTENANCE_EVERY_OPERATION_IS_TOLD_WHEN_TO_RETURN",
        BackendState.IN_MAINTENANCE,
        covers = cells { backend(Routes.Challenge.ID, Maintenance::class) },
    ) { s ->
        everyOperation(s).forEach { (operation, reply) ->
            val maintenance = assertIs<Maintenance>(reply, "$operation: a migration window is its own answer")
            assertTrue(
                maintenance.retryAfter.all(Char::isDigit),
                "$operation: Retry-After is seconds: ${maintenance.retryAfter}",
            )
        }
    }
}

/** One request of every operation the app sends, named — each with inputs any backend would accept. */
private suspend fun everyOperation(s: EdgeSubject<BackendClient>): List<Pair<String, Reply>> {
    val event = s.seeded.eventId
    val device = s.seeded.deviceId
    val challenge = "a-challenge"
    val c = s.port
    return listOf(
        Routes.Challenge.ID to c.send(Routes.Challenge.request()),
        Routes.MintToken.ID to c.send(
            Routes.MintToken.request(MintRequest(device, challenge, AppleAppAttestProof(KEY_ID, "YXR0ZXN0YXRpb24="))),
        ),
        Routes.RenewToken.ID to c.send(Routes.RenewToken.request(RenewRequest(device, "YXNzZXJ0aW9u", challenge))),
        Routes.CreateEvent.ID to c.send(Routes.CreateEvent.request(EventCreate("Unanswered", SEEDED_STARTS_AT, SEEDED_ENDS_AT))),
        Routes.GetEvent.ID to c.send(Routes.GetEvent.request(event)),
        Routes.ChangeEvent.ID to c.send(Routes.ChangeEvent.request(event, EventChange("Unanswered"))),
        Routes.JoinEvent.ID to c.send(Routes.JoinEvent.request(event, device)),
        Routes.PublishManifest.ID to c.send(Routes.PublishManifest.request(event, device, manifestOf(device, emptyList()))),
        Routes.LeaveEvent.ID to c.send(Routes.LeaveEvent.request(event, device, received = false)),
        Routes.EventFiles.ID to c.send(Routes.EventFiles.request(event, urls = false)),
        Routes.DeviceFiles.ID to c.send(Routes.DeviceFiles.request(event, device)),
        Routes.PutDevice.ID to c.send(
            Routes.PutDevice.request(device, DeviceConfig(PushRegistration("apns", "a-token", "development"))),
        ),
    )
}

package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.domain.service.ServiceFault
import app.snapsync.model.CreateOutcome
import app.snapsync.ports.HttpResult
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each backend answer MEANS to the app — the need-shaped services' mappings over the gateway's replies. How an
 * answer becomes a reply is the generated wire's (`BackendGatewayTest`); what the real backend answers is the `Backend`
 * contract's.
 */
class BackendServicesTest {

    private val offline = HttpResult.Unreachable("offline")
    private val maintenance = HttpResult.Response(503, mapOf("Retry-After" to "60"), "maintenance")

    private fun event(name: String = "Party") =
        """{"eventId":"${testEventId("E1").value}","name":"$name","createdAt":"2026-06-01T00:00:00Z","startsAt":"2026-07-01T00:00:00Z",""" +
            """"endsAt":"2026-07-08T00:00:00Z","openUntil":"2026-07-08T00:00:00Z","capacity":40,""" +
            """"deletesAt":"2026-07-31T00:00:00Z","closedAt":null,"completedAt":null}"""

    // ── create ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_served_create_answers_the_minted_id_and_stored_name() = runTest {
        assertEquals(
            CreateOutcome.Created(testEventId("E1"), "Party"),
            servicesAnswering(json(201, event())).creation.create("Party", "s", null),
        )
    }

    @Test
    fun a_created_event_whose_id_is_no_event_id_is_a_reply_the_backend_could_not_have_meant() = runTest {
        assertEquals(
            CreateOutcome.Transient(),
            servicesAnswering(
                json(201, event().replace(testEventId("E1").value, "E1")),
            ).creation.create("Party", "s", null),
        )
    }

    @Test
    fun a_create_carries_the_zone_the_device_is_in_at_that_moment() = runTest {
        val transport = ScriptedTransport { json(201, event()) }
        var zone = "Europe/Berlin"
        val services = BackendServices(
            BackendGateway(exchangeOver(transport), ScriptedCredential(null)),
            identityOf("D", MapSecureStore()),
            zone = { zone },
        )
        fun lastZone() = WireJson.decodeFromString(EventCreate.serializer(), transport.requests.last().body!!).zone
        services.creation.create("Party", "s", null)
        assertEquals("Europe/Berlin", lastZone())
        zone = "America/New_York"
        services.creation.create("Party", "s", null)
        assertEquals("America/New_York", lastZone(), "read at the create, not at composition")
    }

    @Test
    fun a_refused_create_is_the_name_unless_it_names_the_window() = runTest {
        for (body in listOf("invalid name", "invalid body", "invalid keyId")) {
            assertEquals(
                CreateOutcome.InvalidName,
                servicesAnswering(text(400, body)).creation.create("", "s", null),
                body,
            )
        }
        for (body in listOf("invalid endsAt", "invalid startsAt")) {
            assertEquals(
                CreateOutcome.InvalidWindow,
                servicesAnswering(text(400, body)).creation.create("n", "s", "e"),
                body,
            )
        }
    }

    @Test
    fun a_create_still_refused_for_its_credential_is_unverified_not_transient() = runTest {
        assertEquals(
            CreateOutcome.Unverified,
            servicesAnswering(UNAUTHORIZED).creation.create("n", "s", null),
        )
    }

    @Test
    fun every_other_create_answer_is_transient_with_how_the_service_failed() = runTest {
        for ((reply, fault) in listOf(
            text(502, "") to ServiceFault.TROUBLE,
            maintenance to ServiceFault.UPDATING,
            json(201, "<html/>") to ServiceFault.UNREACHABLE,
            offline to ServiceFault.UNREACHABLE,
        )) {
            assertEquals(
                CreateOutcome.Transient(fault),
                servicesAnswering(reply).creation.create("n", "s", null),
                "$reply",
            )
        }
    }

    @Test
    fun a_refusal_no_service_names_is_told_as_the_service_not_reached_never_as_its_failure() = runTest {
        // An answer from a working service that the app has no reading of — here, this build too old — is told as the
        // one condition whose remedy may be the member's own: never as the service failing.
        val tooOld = json(426, """{"error":"app too old","minAppVersion":"9.9"}""")
        assertEquals(
            CreateOutcome.Transient(ServiceFault.UNREACHABLE),
            servicesAnswering(tooOld).creation.create("n", "s", null),
        )
    }
}

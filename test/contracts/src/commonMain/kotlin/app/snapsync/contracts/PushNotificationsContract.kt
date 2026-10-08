package app.snapsync.contracts

import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.ports.Completion
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the push service can do for the app when a clause starts. */
enum class PushState {
    /** A build that names no push project: asking for a token is answered, with a failure. */
    NO_PUSH_PROJECT,

    /** A push service the binding can make hand the app a token and a message, through its own entries ([PushOs]). */
    DELIVERING,
}

/** The push service's side, as the binding plays it through the platform's own entries. */
class PushOs(
    /** The service issues (or rotates) this device's token. */
    val issueToken: (token: String) -> Unit,
    /** A data message arrives; answers whether the app released it within the service's budget. */
    val deliver: (data: Map<String, String>) -> Boolean,
)

/** The port, and — where the binding has one — the service's side. */
class PushUnderTest(val push: PushNotifications, val os: PushOs? = null)

/**
 * What the push entry promises (`docs/architecture.md`, "entry ports"): an ask for a token is answered — a token or a
 * failure, never silence — and a token and a message the service delivers reach the handlers, the message with the
 * completion the app releases once its work is done. What a push RUNS is the composition's.
 */
object PushNotificationsContract : Contract<PushState, PushUnderTest>("PushNotifications") {

    override val clauses = clauses {

        clause(
            "NO_PUSH_PROJECT_AN_ASK_IS_ANSWERED_WITH_A_FAILURE",
            PushState.NO_PUSH_PROJECT,
            covers = cells {
                on<PushNotifications> {
                    answers(PushNotifications::kind).returns()
                    answers(PushNotifications::listen).returns()
                    answers(PushNotifications::register).returns()
                    calls(PushHandlers::onTokenFailure, PlatformError::class)
                }
            },
        ) { subject ->
            val failures = mutableListOf<PlatformError>()
            val tokens = mutableListOf<PushToken>()
            subject.push.listen(
                PushHandlers(
                    onToken = { tokens += it },
                    onTokenFailure = { failures += it },
                    onMessage = { _, c -> c.complete() },
                ),
            )
            assertTrue(subject.push.kind.isNotBlank(), "the registration names its service")
            subject.push.register()
            awaitWithin { failures.isNotEmpty() || tokens.isNotEmpty() }
            assertTrue(tokens.isEmpty(), "no project issues no token")
            assertTrue(failures.single().description.isNotBlank(), "the failure says why")
        }

        clause(
            "DELIVERING_A_TOKEN_REACHES_THE_APP",
            PushState.DELIVERING,
            covers = cells { on<PushNotifications>().calls(PushHandlers::onToken, PushToken::class) },
        ) { subject ->
            val os = kotlin.test.assertNotNull(subject.os, "a delivering binding plays the service")
            val tokens = mutableListOf<PushToken>()
            subject.push.listen(
                PushHandlers(onToken = { tokens += it }, onTokenFailure = {}, onMessage = { _, c -> c.complete() }),
            )
            os.issueToken("contract-token")
            awaitWithin { tokens.isNotEmpty() }
            assertEquals(PushToken("contract-token"), tokens.single())
        }

        clause(
            "DELIVERING_A_MESSAGE_ARRIVES_WHOLE_AND_IS_RELEASED",
            PushState.DELIVERING,
            covers = cells {
                on<PushNotifications> {
                    calls(PushHandlers::onMessage, PushMessage::class, Completion::class)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val os = kotlin.test.assertNotNull(subject.os, "a delivering binding plays the service")
            val payloads = mutableListOf<Map<Any?, *>>()
            subject.push.listen(
                PushHandlers(onToken = {}, onTokenFailure = {}, onMessage = { message, completion ->
                    payloads += message.payload
                    completion.complete()
                }),
            )
            val released = os.deliver(mapOf("eventId" to "contract-event"))
            assertEquals("contract-event", payloads.single()["eventId"], "the payload arrives whole")
            assertTrue(released, "the service learns the app released it, within its budget")
        }
    }
}

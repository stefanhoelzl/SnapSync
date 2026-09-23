package app.snapsync.contracts

import app.snapsync.model.ApnsPushToken
import app.snapsync.ports.PushTokenPublisher
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backend states a [PushTokenPublisher] clause needs. The port publishes for [Seeded.deviceId]. */
enum class PushTokenPublisherState {
    /** A device the edge holds an enrolment for, presenting a credential the edge accepts. */
    ENROLLED,

    /** A device presenting a bearer this edge never issued. */
    FOREIGN_TOKEN,
}

/**
 * What publishing this device's push token promises (capability `port-contracts` — the port's specification).
 *
 * No clause reads the published token back: the edge exposes no read of a device's config, and a setup that
 * reached around its public surface would contract a stand-in rather than the edge. What the app depends on
 * is the result — success means "pushable", and a refusal must arrive as a failed result the registration
 * retries, never a throw into the caller.
 */
object PushTokenPublisherContract :
    Contract<PushTokenPublisherState, EdgeSubject<PushTokenPublisher>>("PushTokenPublisher") {

    fun token(clauseId: String, n: Int = 1) = ApnsPushToken("token$n:$clauseId", "sandbox")

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(state: PushTokenPublisherState, clauseId: String, setup: EdgeSetup): Seeded {
        val identity = if (state == PushTokenPublisherState.FOREIGN_TOKEN) {
            ClientIdentity(SERVED_APP_VERSION, FOREIGN_TOKEN)
        } else {
            ClientIdentity.SERVED
        }
        return Seeded(eventId = setup.freshId(), deviceId = setup.freshId(), identity = identity)
    }

    override val clauses = clauses {

        clause("A_TOKEN_IS_PUBLISHED", PushTokenPublisherState.ENROLLED) { s ->
            assertTrue(s.port.publish(token("A_TOKEN_IS_PUBLISHED")).isSuccess)
        }

        clause("A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST", PushTokenPublisherState.ENROLLED) { s ->
            val id = "A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST"
            assertTrue(s.port.publish(token(id, 1)).isSuccess)
            assertTrue(s.port.publish(token(id, 2)).isSuccess, "a rotation overwrites; it is never a conflict")
        }

        clause("A_FOREIGN_TOKEN_IS_REJECTED", PushTokenPublisherState.FOREIGN_TOKEN) { s ->
            assertTrue(s.port.publish(token("A_FOREIGN_TOKEN_IS_REJECTED")).isFailure, "a refusal is a failed result")
            assertTrue(s.gate.credentialRejected, "the rejection reaches the app, which starts credential recovery")
            assertFalse(s.gate.buildRefused, "a rejected credential is not a refused build")
        }
    }
}

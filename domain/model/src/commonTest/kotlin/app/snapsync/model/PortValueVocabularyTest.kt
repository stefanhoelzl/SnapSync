package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The small values the ports and the commands carry, pinned as their consumers read them: a sealed outcome's cases
 * are values a `when` can tell apart, and a payload case compares by its payload, because a feature decides on the
 * case and a test asserts the answer by equality.
 */
class PortValueVocabularyTest {

    @Test
    fun `a create outcome is one of five distinct answers the created one compared by its event`() {
        assertEveryCaseDistinct(
            listOf(
                CreateOutcome.Created("event", "Picnic"),
                CreateOutcome.InvalidName,
                CreateOutcome.InvalidWindow,
                CreateOutcome.Unverified,
                CreateOutcome.Transient,
            ),
        ) {
            when (it) {
                is CreateOutcome.Created -> "Created"
                CreateOutcome.InvalidName -> "InvalidName"
                CreateOutcome.InvalidWindow -> "InvalidWindow"
                CreateOutcome.Unverified -> "Unverified"
                CreateOutcome.Transient -> "Transient"
            }
        }
        assertValueEquality({ CreateOutcome.Created("event", "Picnic") }, CreateOutcome.Created("event"))
    }

    @Test
    fun `a handoff to the platform was accepted or refused with a reason`() {
        assertEveryCaseDistinct(listOf(Handoff.Accepted, Handoff.Refused("no window"))) {
            when (it) {
                Handoff.Accepted -> "Accepted"
                is Handoff.Refused -> "Refused"
            }
        }
        assertValueEquality({ Handoff.Refused("no window") }, Handoff.Refused("no scene"))
    }

    @Test
    fun `a change was applied or refused with the platform's code`() {
        assertEveryCaseDistinct(listOf(ChangeOutcome.Applied, ChangeOutcome.Refused(3311, "denied"))) {
            when (it) {
                ChangeOutcome.Applied -> "Applied"
                is ChangeOutcome.Refused -> "Refused"
            }
        }
        assertValueEquality({ ChangeOutcome.Refused(3311, "denied") }, ChangeOutcome.Refused(null, "denied"))
    }

    @Test
    fun `a wake was scheduled refused with a detail or is unsupported`() {
        assertEveryCaseDistinct(
            listOf(ScheduleResult.Scheduled, ScheduleResult.Refused("too many"), ScheduleResult.Unsupported),
        ) {
            when (it) {
                ScheduleResult.Scheduled -> "Scheduled"
                is ScheduleResult.Refused -> "Refused"
                ScheduleResult.Unsupported -> "Unsupported"
            }
        }
        assertValueEquality({ ScheduleResult.Refused("too many") }, ScheduleResult.Refused("not permitted"))
    }

    @Test
    fun `the suppression view is ready on an older schema or unavailable with a detail`() {
        assertEveryCaseDistinct(
            listOf(
                SuppressionReadiness.Ready,
                SuppressionReadiness.OldSchema,
                SuppressionReadiness.Unavailable("locked"),
            ),
        ) {
            when (it) {
                SuppressionReadiness.Ready -> "Ready"
                SuppressionReadiness.OldSchema -> "OldSchema"
                is SuppressionReadiness.Unavailable -> "Unavailable"
            }
        }
        assertValueEquality({ SuppressionReadiness.Unavailable("locked") }, SuppressionReadiness.Unavailable("corrupt"))
    }

    @Test
    fun `a delivered link and a platform error compare by what they carry`() {
        assertValueEquality(
            { LinkDelivery(hook = "onOpenURL", isWebLink = true, activityType = null, url = "https://x") },
            LinkDelivery(hook = "onOpenURL", isWebLink = false, activityType = null, url = "https://x"),
        )
        assertValueEquality({ PlatformError("denied") }, PlatformError("restricted"))
    }

    @Test
    fun `a push token compares by its value`() {
        assertValueEquality({ PushToken("abc") }, PushToken("def"))
    }

    /**
     * Holding the value IS the whole contract of these two: each is the platform's payload handed through untouched
     * (an APNs/FCM dictionary, a selection's assets), read by the core and never compared.
     */
    @Test
    fun `a push message and a selection snapshot hand their payload through untouched`() {
        val payload = mapOf<Any?, Any?>("seq" to 3)
        assertSame(payload, PushMessage(payload).payload)
        val assets = emptyList<RawAsset>()
        assertSame(assets, SelectionSnapshot(assets).assets)
    }
}

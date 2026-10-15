package app.snapsync.domain.membership

import app.snapsync.domain.event.EventId
import kotlin.jvm.JvmInline

// The membership's evidence (`docs/patterns.md`, "Evidence types"): a step that needs a verdict to have said yes
// requires its evidence, and each evidence has one mint, its constructor's opt-in pinned to that one site
// (`OptInPinsTest`). Never stored, never serialized, never a data class.

/** Only [DeletionVerdict] mints a [ConfirmedDeletion]. */
@RequiresOptIn("Only DeletionVerdict confirms a deletion.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsConfirmedDeletion

/** Only the enrolment use case mints an [Enrolled]. */
@RequiresOptIn("Only the enrolment use case says the backend admitted this device.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsEnrolled

/** Only the membership store's leave mints a [LeftMembership]. */
@RequiresOptIn("Only the membership store's leave says the leave committed.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsLeftMembership

/** Only the membership store's read mints a [ConfirmedNotJoined]. */
@RequiresOptIn("Only the membership store's read says this device is not joined.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsConfirmedNotJoined

/** The server confirmed [event]'s photos deleted: a membership may be marked deleted. */
@JvmInline
value class ConfirmedDeletion @MintsConfirmedDeletion constructor(val event: EventId)

/** The backend admitted this device to [membership]'s event, with [membership]'s share: it may be entered. */
@JvmInline
value class Enrolled @MintsEnrolled constructor(val membership: Membership.Active)

/** The leave of [event] committed: the membership, its pending leave and its checks, in one transaction. */
@JvmInline
value class LeftMembership @MintsLeftMembership constructor(val event: EventId)

/** The store answered with no membership — never an unreadable one: what is left over may be swept. */
class ConfirmedNotJoined @MintsConfirmedNotJoined constructor()

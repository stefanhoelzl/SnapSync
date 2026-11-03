package app.snapsync.domain.membership

/**
 * Evidence that a membership's uploads may end — the extension deregistered, the app's wakes disarmed, its transfers
 * cancelled. Exactly three facts are: the leave committed ([LeftMembership]), the store holds no membership after a
 * leave that did not finish ([ConfirmedNotJoined]), and the server deleted the event's photos ([Membership.Deleted]).
 * Sealed, so a fourth reason to stop uploading is a change to the domain, not a new call site.
 */
sealed interface EndsUploads

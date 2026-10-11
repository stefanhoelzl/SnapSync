package app.snapsync.services.config

import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.MembershipRead

/**
 * The next [ConfigService.config] value after a trigger-time re-read (migration step 12: every
 * OS-callback flow re-reads the membership before acting on it, replacing the deleted unlock-hook
 * repair). Pure so the one branch that matters is tested in `commonTest`:
 *
 * - a **conclusive** read ([ConfigRead.Joined] / [ConfigRead.None]) replaces the value;
 * Absence: the returned null means "definitively not joined" and ONLY that — the three-state
 * [ConfigRead] is precisely what keeps "could not tell" out of it, by retaining the last good value
 * instead. This function is where that law is enforced for the membership.
 *
 * - an **unreadable** read ([ConfigRead.Unavailable]) **retains** [current] — the same
 *   keep-the-last-good posture as the status counts. Under the old cadence (reload only at the
 *   unlock notification) an unreadable reload was unreachable; at trigger cadence a transient read
 *   failure on a foreground entry would otherwise clear a good membership and flip the screen to
 *   the setup gate.
 */
fun configAfterReload(read: ConfigRead, current: EventConfig?): EventConfig? = when (read) {
    is ConfigRead.Joined -> read.config
    ConfigRead.None -> null
    is ConfigRead.Unavailable -> current
}

/**
 * The next [MembershipRead] after a re-read — [configAfterReload]'s three-valued twin. A conclusive read replaces
 * it; an unreadable one retains the last conclusive answer, and is [MembershipRead.Unreadable] only when there has
 * never been one.
 */
fun membershipAfterReload(read: ConfigRead, current: MembershipRead): MembershipRead = when (read) {
    is ConfigRead.Joined -> MembershipRead.Member(read.config)
    ConfigRead.None -> MembershipRead.NotMember
    is ConfigRead.Unavailable -> current
}

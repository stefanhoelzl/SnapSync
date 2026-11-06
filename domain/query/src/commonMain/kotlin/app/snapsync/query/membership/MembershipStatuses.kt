package app.snapsync.query.membership

import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.RestoreAnswer
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.query.RequestStatuses

/** The membership's requests, each answered on its own status. */
class MembershipStatuses {
    val join = RequestStatuses<JoinCommit>()
    val settings = RequestStatuses<SettingsResult>()
    val switch = RequestStatuses<SwitchVerdict>()
    val rename = RequestStatuses<RenameResult>()
    val restore = RequestStatuses<RestoreAnswer>()

    /** Trying again to read the membership: answered once it was read again and what opening the app runs has run. */
    val read = RequestStatuses<Unit>()
}

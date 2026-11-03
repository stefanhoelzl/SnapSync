package app.snapsync.data.receiving

import app.snapsync.domain.receiving.UnionTrigger

/** The trigger a union read tells the backend (`X-SnapSync-Trigger`), which records it. */
internal val UnionTrigger.wire: String
    get() = when (this) {
        UnionTrigger.PUSH -> "push"
        UnionTrigger.WAKE -> "wake"
        UnionTrigger.FOREGROUND -> "foreground"
        UnionTrigger.JOIN -> "join"
        UnionTrigger.GRANT -> "grant"
        UnionTrigger.RECONFIGURE -> "reconfigure"
        UnionTrigger.LEAVE_CHECK -> "leave-check"
        UnionTrigger.RECEIVED_CHECK -> "received-check"
    }

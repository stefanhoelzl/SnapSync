package app.snapsync.domain.upload

import app.snapsync.domain.sharing.PhotoAccess

/**
 * **What a membership transition, or the app's launch, does to the two uploaders** — decided afresh at each, from the
 * registration fact and the grant read at that moment; nothing is held (decision record
 * `changes/archive/2026-09-22-both-uploaders-active`, D5). The extension's registration spans the membership: made at
 * the join wherever the OS allows it, and removed only when the membership's uploads end ([EndsUploads]).
 */
enum class UploaderChange {
    /** A join — a first join or a switch, after the share-set load and the save. */
    JOIN,

    /** A settings change, in any direction: the selection policy decides whether anything uploads. */
    RECONFIGURE,

    /** The app launched with its UI. */
    LAUNCH,

    /** A real change of the photo grant — never the replayed value the launch reconciled against. */
    GRANT_CHANGED,

    ;

    /**
     * Whether this change registers the extension. A join FORCES the toggle where the extension is registrable — the
     * one transition allowed to repair a stale record a bare enable would fail on (3202). A launch or a grant change
     * registers only where the OS reads no record ([OsUploaderStanding] — read only while registrable, since the
     * OS's read is trustworthy only under a full grant), so it wipes no in-flight job. A settings change never touches
     * it.
     */
    fun registration(admission: OsUploaderAdmission, standing: OsUploaderStanding): Registration = when (this) {
        JOIN -> forced(admission)
        LAUNCH, GRANT_CHANGED -> compared(admission, standing)
        RECONFIGURE -> Registration.LEAVE
    }

    /** Whether this change needs a membership held to do anything: every one but the join that made it. */
    val needsMembership: Boolean get() = this != JOIN

    private fun forced(admission: OsUploaderAdmission) = when (admission) {
        OsUploaderAdmission.REGISTRABLE -> Registration.REGISTER
        OsUploaderAdmission.NOT_REGISTRABLE -> Registration.LEAVE
    }

    private fun compared(admission: OsUploaderAdmission, standing: OsUploaderStanding) = when (admission) {
        OsUploaderAdmission.REGISTRABLE -> absent(standing)
        OsUploaderAdmission.NOT_REGISTRABLE -> Registration.LEAVE
    }

    private fun absent(standing: OsUploaderStanding) = when (standing) {
        OsUploaderStanding.CONFIRMED -> Registration.LEAVE
        OsUploaderStanding.NOT_CONFIRMED -> Registration.REGISTER
    }
}

/** Whether a change registers the upload extension, or leaves its registration as it is. */
enum class Registration { REGISTER, LEAVE }

/** Whether the app's own uploader is armed: its wakes scheduled under a usable grant, cancelled under none. */
enum class UploaderArming {
    ARM,
    DISARM,

    /** Left as it was — a transition that could not read the membership it needs defers to the next one. */
    UNCHANGED,

    ;

    companion object {
        /** Armed while the grant lets the app read photos at all — a partial grant included. */
        fun of(access: PhotoAccess): UploaderArming = when (access) {
            PhotoAccess.FULL, PhotoAccess.LIMITED -> ARM
            PhotoAccess.NONE -> DISARM
        }
    }
}

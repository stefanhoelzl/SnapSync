package app.snapsync.domain.upload

import app.snapsync.domain.sharing.PhotoAccess

/** Whether the OS carries the upload-extension mechanism at all: iOS 26.1 and later. */
enum class OsSupport {
    CARRIED,
    NOT_CARRIED,
    ;

    companion object {
        /** What the platform said: whether it [carried] the mechanism. */
        fun of(carried: Boolean): OsSupport = when (carried) {
            true -> CARRIED
            false -> NOT_CARRIED
        }
    }
}

/** The development switch for the extension: on everywhere but in a test build that turned it off. */
enum class ExtensionSwitch { ON, OFF }

/** The development switch for the app's own uploader: on everywhere but in a test build that turned it off. */
enum class AppUploaderSwitch { ON, OFF }

/**
 * Whether the upload extension **may be registered** right now (decision record `changes/both-uploaders-active`, D4).
 * Both uploaders run beside each other, so nothing here chooses which one runs; what the transitions need is whether
 * the OS will accept a registration at all:
 *
 * - **Never below iOS 26.1** ([OsSupport.NOT_CARRIED]): the registration selector does not exist there, and calling it
 *   would trap and abort the process — so no switch can make it registrable there.
 * - **Only under a full grant**: under a partial one every registration write is refused (`3311`, measured SE2/26.6),
 *   and under no access there is nothing to register for.
 *
 * Re-decided at every use, never once per process: the grant changes at runtime, and a stale answer would register
 * over a refused grant.
 */
enum class OsUploaderAdmission {
    REGISTRABLE,
    NOT_REGISTRABLE,
    ;

    companion object {
        fun of(support: OsSupport, access: PhotoAccess, extension: ExtensionSwitch): OsUploaderAdmission =
            when (support) {
                OsSupport.CARRIED -> granted(access, extension)
                OsSupport.NOT_CARRIED -> NOT_REGISTRABLE
            }

        private fun granted(access: PhotoAccess, extension: ExtensionSwitch) = when (access) {
            PhotoAccess.FULL -> switched(extension)
            PhotoAccess.LIMITED, PhotoAccess.NONE -> NOT_REGISTRABLE
        }

        private fun switched(extension: ExtensionSwitch) = when (extension) {
            ExtensionSwitch.ON -> REGISTRABLE
            ExtensionSwitch.OFF -> NOT_REGISTRABLE
        }
    }
}

/**
 * What the OS says of the extension's registration — trusted only under a full grant, so asked only when the extension
 * is registrable; [NOT_ASKED] otherwise, since an answer then would mean nothing.
 */
enum class OsRegistration { REGISTERED, NOT_REGISTERED, UNSUPPORTED, NOT_ASKED }

/**
 * Whether the OS's own uploader stands in for looking for new photos: it is woken by a new photo, so the app need not
 * look. Only the OS's own answer counts, and only while the extension may be registered — whether registering is
 * allowed says nothing about whether it happened.
 */
enum class OsUploaderStanding {
    CONFIRMED,
    NOT_CONFIRMED,
    ;

    companion object {
        fun of(admission: OsUploaderAdmission, registration: OsRegistration): OsUploaderStanding = when (admission) {
            OsUploaderAdmission.REGISTRABLE -> answered(registration)
            OsUploaderAdmission.NOT_REGISTRABLE -> NOT_CONFIRMED
        }

        private fun answered(registration: OsRegistration) = when (registration) {
            OsRegistration.REGISTERED -> CONFIRMED
            OsRegistration.NOT_REGISTERED, OsRegistration.UNSUPPORTED, OsRegistration.NOT_ASKED -> NOT_CONFIRMED
        }
    }
}

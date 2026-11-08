package app.snapsync.domain.upload

/** Only the grant's read mints a [FullGrant]. */
@RequiresOptIn("Only the grant's read says photo access is full.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsFullGrant

/**
 * The member's photo grant is full right now — what registering the upload extension requires: iOS refuses either
 * registration write under a partial grant (3311), and the extension creates only under a full one. Minted per read,
 * never cached or stored: the grant changes at runtime.
 */
class FullGrant @MintsFullGrant constructor()

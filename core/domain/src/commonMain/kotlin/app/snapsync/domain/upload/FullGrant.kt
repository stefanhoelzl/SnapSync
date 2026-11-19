package app.snapsync.domain.upload

/** Only the grant's read mints a [FullGrant]. */
@RequiresOptIn("Only the grant's read says photo access is full.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsFullGrant

/**
 * The member's photo grant is full right now. Both registering and deregistering the upload extension require it:
 * iOS refuses either write under a partial grant (3311), refuses a disable with no grant at all with codes no SDK
 * declares, and the extension creates only under a full grant. Minted per read, never cached or stored: the grant
 * changes at runtime.
 */
class FullGrant @MintsFullGrant constructor()

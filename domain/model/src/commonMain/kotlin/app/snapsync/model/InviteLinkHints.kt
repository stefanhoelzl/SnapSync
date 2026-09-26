package app.snapsync.model

/**
 * Whether the join gate acts on an invite link's **dev/test hints** — `autoJoin` and the overrides that ride
 * with it (`minPhotoDate`, `maxPhotoDate`, `direction`, `saveToAlbum`; see [EventLinkPayload]) — capability
 * `join-event`, "Joining happens only on confirmation".
 *
 * The decoder accepts those keys from ANY link, so what a link *says* can never be what authorizes a headless
 * join: a crafted QR carrying `autoJoin=true` would otherwise join without a tap, leave the member's current
 * event, and start sharing. The authority is this value instead, supplied by the composition root.
 *
 * **A production build is always [Ignored]**, and that is structural rather than a runtime check: the only
 * answer of [Honoured] is the control channel's development controls (and its JVM host's world), whose source is compiled only
 * into a build made with `-Psnapsync.rig=true`. Under [Ignored] an `autoJoin` link opens the ordinary join
 * confirmation and every override is discarded — the join screen seeds its own defaults, as for any invite.
 */
enum class InviteLinkHints {
    /** The shipped behaviour: every link is an ordinary invite; hints are logged and dropped. */
    Ignored,

    /** Rig builds only: `autoJoin` auto-confirms, honouring the link's overrides (still clamped in `JoinEvent`). */
    Honoured,
}

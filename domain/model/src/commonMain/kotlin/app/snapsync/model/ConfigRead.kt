package app.snapsync.model

/**
 * A single read of the persisted config, with **three** outcomes — the distinction every upload cycle's
 * entry gate depends on.
 *
 * "No config" means *this device is not joined*. So an **unreadable** config — the normal state on a
 * locked device before this change, since the item was stored `WhenUnlocked` — must never be reported as
 * an **absent** one. It used to be, and the result was a *false leave* on every OS-scheduled invocation,
 * tearing down join state that the next readable cycle then paid to rebuild — for ever.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
sealed interface ConfigRead {

    /** A config is persisted and decodes. */
    data class Joined(val config: EventConfig) : ConfigRead

    /**
     * There is definitively no usable config: the config file is genuinely missing. This is the only
     * outcome that reads as not joined, and since the Stage-2 fallback deletion
     * it is reached from **one** fact — the file's not-found error class — with no second store
     * consulted (capability `photo-sharing`).
     */
    data object None : ConfigRead

    /**
     * The store could not be read (protected data unavailable, a file this build cannot interpret). Says **nothing**
     * about membership. [detail] is diagnostic only — what a device log needs to tell the causes apart; nothing
     * branches on it.
     */
    data class Unavailable(val detail: String) : ConfigRead
}

/** What a reader acting on the membership can know about it right now. */
sealed interface MembershipRead {
    /** Joined to [config]'s event. */
    data class Member(val config: EventConfig) : MembershipRead

    /** Definitively not joined — the config file is genuinely missing, or was cleared by a leave. */
    data object NotMember : MembershipRead

    /**
     * Nothing conclusive has been read in this process yet: the store was unreadable (protected data unavailable
     * on a locked device, a foreign file) since launch. Says NOTHING about membership — the reader defers.
     */
    data object Unreadable : MembershipRead
}

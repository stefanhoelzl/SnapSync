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

    /** The store could not be read (protected data unavailable). Says **nothing** about membership. */
    data class Unavailable(val status: Int) : ConfigRead
}

/**
 * The three answers a raw config-**file** read can give. The App-Group file is the config's **only**
 * storage: migration step 11a made it the storage of record, the finale ended the Keychain
 * write-through, and the Stage-2 change (`changes/archive/…-retire-legacy-config-fallback`) deleted
 * the read-only legacy-Keychain fallback that stood behind [Missing]. The platform adapter maps its
 * file-IO errors onto these using the pure absence classifier (`isConfigFileAbsence`, in
 * `:adapter:ios:ext-safe` — its inputs are an `NSError` domain and code, a platform encoding, so
 * translating them is an adapter's job) so "genuinely missing" admits **only** the not-found error
 * class and every other failure stays on the unreadable side.
 *
 * **That classifier is now solely load-bearing.** While the fallback existed, a wrong [Missing] was
 * caught downstream: the fallback found the legacy item, answered joined, and the device stayed
 * joined. There is no second opinion any more — a misclassified read error reads the device as not
 * joined (it uploads nothing, and the screen returns to the setup gate), so widening the not-found
 * whitelist is a change to the leave decision, not an error-handling detail.
 */
sealed interface ConfigFileRead {

    /** The file exists and was read; [text] is its (not yet decoded) content. */
    data class Content(val text: String) : ConfigFileRead

    /**
     * The file genuinely does not exist (not-found error class **only**) — **definitively not
     * joined**, the sole road to "this device left the event", reached with nothing else consulted.
     * An App-Group container dies with the install, so this is also what makes a reinstall a leave
     * (capability `photo-sharing`).
     */
    data object Missing : ConfigFileRead

    /** The read failed for any other reason (e.g. protected data unavailable). Never absence. */
    data class Failed(val status: Int, val detail: String) : ConfigFileRead
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

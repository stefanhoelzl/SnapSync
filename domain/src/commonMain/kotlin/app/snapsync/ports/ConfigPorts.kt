package app.snapsync.ports

import app.snapsync.model.EventConfig

import kotlinx.coroutines.flow.StateFlow

/**
 * A single read of the persisted config, with **three** outcomes — the distinction the upload
 * extension's reconciliation depends on.
 *
 * "No config" means *this device left the event* to the reconciler: it clears the persisted
 * `joinedEventId` marker (capability `event-rejoin-reconciliation`). So an **unreadable** config —
 * the normal state on a locked device before this change, since the item was stored `WhenUnlocked` —
 * must never be reported as an **absent** one. It used to be, and the result was a *false leave* on
 * every OS-scheduled invocation: the marker was cleared, and the next readable cycle paid for a full
 * re-join reconciliation (a device listing, an atomic ledger clear-and-seed, and a discovery-cursor
 * reset forcing a complete library re-enumeration) — for ever, without the marker ever settling.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
sealed interface ConfigRead {

    /** A config is persisted and decodes. */
    data class Joined(val config: EventConfig) : ConfigRead

    /**
     * There is definitively no usable config: the item is absent, **or** it is a legacy item that does
     * not decode (e.g. one written before `minPhotoDate` existed — capability `photo-selection-policy`,
     * where reading as no-config is the deliberate safe outcome). This is the only outcome that may
     * drive the leave-side reconciliation.
     */
    data object None : ConfigRead

    /** The store could not be read (protected data unavailable). Says **nothing** about membership. */
    data class Unavailable(val status: Int) : ConfigRead
}

/**
 * The three-state read port. [ConfigSource]'s `StateFlow` deliberately keeps its two-state shape for
 * the UI (which cannot run while protected data is unavailable anyway); readers that *act on absence*
 * — the extension's cycle — must use this instead.
 */
interface ConfigReader {
    fun read(): ConfigRead
}

/**
 * Map a raw Keychain read to a [ConfigRead]. Pure, so the branch that matters — *unreadable is not
 * absent* — is tested on JVM **and** the iOS simulator. [decode] returns `null` for an item that does
 * not decode, which is a [ConfigRead.None] (an undecodable item is genuinely unusable), never an
 * [ConfigRead.Unavailable] (which would mean "try again later").
 */
fun configReadFrom(read: KeychainRead, decode: (String) -> EventConfig?): ConfigRead = when (read) {
    is KeychainRead.Found -> decode(read.value)?.let(ConfigRead::Joined) ?: ConfigRead.None
    KeychainRead.Absent -> ConfigRead.None
    is KeychainRead.Unavailable -> ConfigRead.Unavailable(read.status)
}

/**
 * The state port for the active config: a level-triggered holder whose current value is always
 * available synchronously — the persisted [EventConfig] (the joined `eventId` plus an optional
 * fetched `name`), or `null` when none has been provisioned yet. The setup gate observes this to
 * decide whether the "joined an event" step is satisfied. Like the permission seam, truth arrives
 * here and nowhere else. Combining the `eventId` with the compile-time upload host into the edge
 * upload URL is the consuming composition root's job, not this seam's.
 *
 * **This port cannot express "unreadable"** — `null` here means "no config, as far as this process can
 * tell". That is fine for the UI, and fatal for the reconciler; see [ConfigReader].
 */
interface ConfigSource {
    val config: StateFlow<EventConfig?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a config equal (field-for-field, incl. `name`) to the current one is an idempotent no-op;
 * saving a config differing in `eventId` **or** `name` replaces it and emits (a name-only change
 * updates the title without any ledger effect — the switch-reset on an `eventId` change is
 * orchestrated by the provision path, not this seam; see the event-link spec). [clear] is the inverse:
 * it removes the persisted config and updates the [ConfigSource] to `null` (an idempotent no-op when
 * none is persisted), and — like [save] — leaves the ledger untouched (the caller orchestrates any
 * ledger reset; see the `leave-event` capability). Implementations typically also implement
 * [ConfigSource] as one platform adapter; consumers depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: EventConfig)

    suspend fun clear()
}

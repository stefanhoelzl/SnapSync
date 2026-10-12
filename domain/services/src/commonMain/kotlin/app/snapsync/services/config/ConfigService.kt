package app.snapsync.services.config

import app.snapsync.model.CaptureDate
import app.snapsync.model.ConfigRead
import app.snapsync.model.DeletesAt
import app.snapsync.model.EventConfig
import app.snapsync.model.MembershipRead
import app.snapsync.model.confirmedGone
import app.snapsync.model.hasEnded
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.shareHasEnded
import app.snapsync.ports.Clock
import app.snapsync.services.databases.LegacySource
import app.snapsync.services.databases.SharedDatabase
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * The membership's config: [ConfigSource]/[ConfigStore]/[ConfigReader] over the one
 * `membership` row of the shared database ([SharedDatabase]), and nothing else. Both processes read it; the app writes it.
 *
 * That makes **reinstall = left the event** real: the App-Group container — and the database in it — dies with the
 * install, so a reinstalled device reads definitively not joined and rejoins only by re-scanning the invite.
 *
 * ⚠️ **"No row" is solely load-bearing for the leave decision.** Only a database that ANSWERED, with no row, reads as
 * [ConfigRead.None] — definitively not joined. A database that cannot be opened or read (a locked device's), a
 * membership still in its legacy file ([LegacySource.MEMBERSHIP] not copied yet), or a row the model refuses is
 * **unreadable** ([ConfigRead.Unavailable]), and the caller defers.
 *
 * **Seeded on first use, never at construction**: building the composition opens no database, so [config] reads the
 * row the first time something asks for it — a foreground launch as it builds its screen, a wake when its flow first
 * looks. A seed before the first unlock reads unreadable, never absent, and every trigger flow calls [reload] to repair
 * it before acting. **Readers that act on the absence of a config must use [read], not [config]** — see [ConfigRead].
 */
class ConfigService(
    private val db: SharedDatabase,
    /** The wall clock the membership's own deadline is read against ([isPastDeletion]). */
    private val clock: Clock,
    private val log: Logger = Logger.withTag("membershipConfig"),
) {

    /**
     * What this process holds of the membership, in its two forms — written together, so they never disagree. Seeded
     * by the first read, at the first use of either — see the class note.
     */
    private class Held(initial: ConfigRead) {
        val config = MutableStateFlow(initial.joinedOrNull())

        /** `Unreadable` only until a conclusive read. */
        @Volatile
        var membership: MembershipRead = membershipAfterReload(initial, MembershipRead.Unreadable)

        fun take(config: EventConfig?, membership: MembershipRead) {
            this.config.value = config
            this.membership = membership
        }
    }

    private val held: Held by lazy { Held(read()) }

    /**
     * The active membership as a level-triggered holder whose current value is always available synchronously — the
     * persisted [EventConfig], or `null` when none is provisioned **as far as this process can tell**. That is fine
     * for the UI, and fatal for anything that acts on absence: those read [membership] or [read].
     */
    val config: StateFlow<EventConfig?> get() = held.config

    /**
     * The membership as three answers (decision record `harden-seam-bug-classes`,
     * D11): [config]'s `null` merges "not joined" with "could not read it yet", and a reader that ACTS on absence —
     * the upload transitions, the silent-push receivers — must tell them apart and defer on the second.
     *
     * Read fresh at each use, never held. [MembershipRead.Unreadable] only until a conclusive read.
     */
    val membership: MembershipRead get() = held.membership

    suspend fun save(config: EventConfig) {
        // Deliberately NO equal-config early return: [config] can lag a cross-process writer, so an outer guard
        // could skip a write the row actually needs. Rewriting an equal row is harmless, and the [StateFlow]
        // conflates equal values, so the port's no-redundant-emission contract holds. A failed write throws.
        db.use { it.membershipQueries.saveMembership(config) }
        held.take(config, MembershipRead.Member(config))
    }

    suspend fun clear() {
        // A throw propagates: the row stays, this build stays joined, and the leave retries visibly rather than
        // half-completing. An unreadable database is NOT "nothing to delete" — returning there would null the flow
        // while a row it never touched survived to resurrect the membership at the next launch.
        db.use { it.membershipQueries.clearMembership() }
        held.take(null, MembershipRead.NotMember)
    }

    /** The three-state read: the membership row, and nothing else. Never raises. */
    fun read(): ConfigRead {
        val read = runCatchingCancellable {
            db.use { database ->
                when {
                    !db.imported(LegacySource.MEMBERSHIP) ->
                        ConfigRead.Unavailable("the membership is still in its legacy file")
                    else -> database.membershipQueries.selectMembership().executeAsOneOrNull()
                        ?.let { ConfigRead.Joined(it.toConfig()) }
                        ?: ConfigRead.None
                }
            }
        }.getOrElse { ConfigRead.Unavailable("the shared database is unreadable: $it") }
        if (read is ConfigRead.Unavailable) {
            log.w { "membership unreadable (${read.detail}) — NOT 'no config'; caller must defer" }
        }
        return read
    }

    /**
     * The joined event's config for a reader that may run before this process re-read the row: the held [config],
     * else the row's — `null` when there is none or it cannot be read now. Never a reader that acts on absence.
     */
    fun joinedOrRead(): EventConfig? = held.config.value ?: when (val read = read()) {
        is ConfigRead.Joined -> read.config
        else -> null
    }

    /**
     * Re-read the row into [config]: cross-process writers do not notify this process's [StateFlow], and a
     * pre-first-unlock seed read `null`. The trigger flows call this at every OS entry, so an
     * **unreadable** read retains the last good value (`configAfterReload`): at this cadence a transient failure
     * would otherwise clear a good membership mid-session and flip the screen to the setup gate.
     */
    fun reload() {
        val read = read()
        held.take(configAfterReload(read, held.config.value), membershipAfterReload(read, held.membership))
    }

    /**
     * Has this membership's own retention deadline [deletesAt] passed? The self-leave's
     * OFFLINE witness — see [confirmedGone] for why it is exact.
     */
    fun isPastDeletion(deletesAt: DeletesAt): Boolean = confirmedGone(deletesAt, CaptureDate.of(clock.now()))

    /** Now, on the clock every membership-time decision here reads — what a policy is derived at. */
    fun now(): CaptureDate = CaptureDate.of(clock.now())

    /**
     * Has [config]'s event range ended? Read on the same clock as [isPastDeletion]; an
     * end not yet learned has not passed — see [hasEnded].
     */
    fun hasEnded(config: EventConfig): Boolean = config.hasEnded(CaptureDate.of(clock.now()))

    /**
     * Has [config]'s OWN capture range ended — what its share is settled by? Read on the same clock as [hasEnded]; see
     * [shareHasEnded].
     */
    fun shareHasEnded(config: EventConfig): Boolean = config.shareHasEnded(CaptureDate.of(clock.now()))

    /** `null` for both *absent* and *unreadable* — acceptable for the UI-facing [config], never for the reconciler. */
    /** The joined event's id, or `null` while not joined — the scope every per-event read takes. */
    fun activeEventId(): String? = held.config.value?.eventId

    /**
     * Whether the membership receives others' photos — three-valued, no fallback: `null`
     * while not joined, so a caller arms nothing. A membership whose event's photos are deleted receives nothing more.
     */
    fun downloadsEnabled(): Boolean? = held.config.value?.let { it.direction.includesDownload && !it.deleted }

    /**
     * Whether a fresh read finds a joined membership whose OWN range has ended ([shareHasEnded]) — what a manifest
     * published now is settled by. An unreadable config or none is "not ended".
     */
    fun freshReadShareHasEnded(): Boolean = read().joinedOrNull()?.let(::shareHasEnded) == true
}

private fun ConfigRead.joinedOrNull(): EventConfig? = (this as? ConfigRead.Joined)?.config

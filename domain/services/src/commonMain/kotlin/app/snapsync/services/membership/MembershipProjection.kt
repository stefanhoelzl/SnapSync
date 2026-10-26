package app.snapsync.services.membership

import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.DeletionVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.EncryptionText
import app.snapsync.model.EventConfig
import app.snapsync.model.MemberCounts
import app.snapsync.model.hasEnded
import app.snapsync.model.shareHasEnded
import app.snapsync.ports.Clock
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * The membership as the old zones read it — MIGRATING (`PENDING_CLEANUPS.md`): the `EventConfig` each of them was
 * written against, projected from the data layer's [MembershipRepository], until each moves under the layers and reads
 * the membership itself. It reads, and writes only [markOver] — the server's word an old zone's request heard.
 */
class MembershipProjection(private val repository: MembershipRepository, private val clock: Clock) {

    /** The membership as a config, `null` while none is held as far as this process can tell. */
    val config: StateFlow<EventConfig?> = ConfigFlow(repository)

    /** The membership as last read conclusively — what a reader that acts on absence reads. */
    val membership: MembershipRead get() = repository.state.value

    /** The membership as the store holds it now. */
    fun read(): MembershipRead = repository.read()

    /**
     * The server answered the shared set's publish that [event]'s photos were deleted — its positive word: the active
     * membership of that event is marked so, and stays, shown as over. MIGRATING with the publisher
     * (`PENDING_CLEANUPS.md`); one that cannot be written is the next refresh's to mark.
     */
    fun markOver(event: EventId) {
        val active = (read() as? MembershipRead.Joined)?.membership as? Membership.Active ?: return
        if (active.event.id == event) {
            repository.markDeleted(
                active,
                DeletionVerdict.answeredOver(active.event).evidence,
            )
        }
    }

    /** Re-read the membership: another process may have changed it. */
    fun reload() = repository.reload()

    /** The joined event's config — held, else read now — or `null` when none is held or it cannot be read. */
    fun joinedOrRead(): EventConfig? = config.value ?: read().config()

    /** The joined event's id, or `null` while not joined — the scope every per-event read takes. */
    fun activeEventId(): EventId? = config.value?.eventId

    /** Now, on the clock every membership-time decision here reads. */
    fun now(): Timestamp = Timestamp.of(clock.now())

    /** Whether [config]'s event range has ended. */
    fun hasEnded(config: EventConfig): Boolean = config.hasEnded(now())

    /** Whether [config]'s own capture range has ended — what its share is settled by. */
    fun shareHasEnded(config: EventConfig): Boolean = config.shareHasEnded(now())

    /** Whether the membership receives others' photos; `null` while not joined. A deleted one receives nothing more. */
    fun downloadsEnabled(): Boolean? = config.value?.let { it.direction.includesDownload && !it.deleted }

    /** Whether a fresh read finds a membership whose own range has ended. An unreadable one or none is "not ended". */
    fun freshReadShareHasEnded(): Boolean =
        read().config()?.let(::shareHasEnded) == true
}

/** [this] membership as the config the old zones read. */
fun Membership.asConfig(): EventConfig = EventConfig(
    eventId = event.id,
    name = event.name.text,
    minPhotoDate = share.range.from,
    startsAt = event.range.start,
    endsAt = event.range.end,
    maxPhotoDate = share.range.until,
    deletesAt = event.deletesAt,
    direction = share.direction,
    saveToAlbum = share.saveToAlbum,
    members = event.members?.let { MemberCounts(it.active, it.settled, it.waiting) },
    openUntil = event.openUntil,
    deleted = this is Membership.Deleted,
    keyId = EncryptionText.of(event.encryption),
)

private fun MembershipRead.config(): EventConfig? = when (this) {
    is MembershipRead.Joined -> membership.asConfig()
    is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
}

/** The repository's state as a config — distinct, since "not joined" and "unreadable" both read as none. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ConfigFlow(private val repository: MembershipRepository) : StateFlow<EventConfig?> {
    override val value: EventConfig? get() = repository.state.value.config()
    override val replayCache: List<EventConfig?> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<EventConfig?>): Nothing {
        var last: Any? = Unset
        repository.state.collect { read ->
            val config = read.config()
            if (config != last) collector.emit(config)
            last = config
        }
    }

    private object Unset
}

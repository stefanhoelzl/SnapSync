package app.snapsync.services.membership

import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.Membership
import app.snapsync.format.EncryptionText
import app.snapsync.model.EventConfig
import app.snapsync.model.MemberCounts
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * The membership as the old zones read it — MIGRATING (`PENDING_CLEANUPS.md`): the `EventConfig` each of them was
 * written against, projected from the data layer's [MembershipRepository], until each moves under the layers and reads
 * the membership itself. It only reads.
 */
class MembershipProjection(private val repository: MembershipRepository) {

    /** The membership as a config, `null` while none is held as far as this process can tell. */
    val config: StateFlow<EventConfig?> = ConfigFlow(repository)

    /** The membership as the store holds it now. */
    fun read(): MembershipRead = repository.read()

    /** Re-read the membership: another process may have changed it. */
    fun reload() = repository.reload()

    /** The joined event's config — held, else read now — or `null` when none is held or it cannot be read. */
    fun joinedOrRead(): EventConfig? = config.value ?: read().config()

    /** The joined event's id, or `null` while not joined — the scope every per-event read takes. */
    fun activeEventId(): EventId? = config.value?.eventId
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
    saveToAlbum = share.album == AlbumChoice.SaveToAlbum,
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

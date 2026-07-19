package app.snapsync.feature.membership

import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore

/**
 * The event-name refresh **rule** (capability `join-event`): a freshly fetched event name is stored
 * into the persisted membership only if it still belongs to the joined event and actually changed.
 * The *fetch* is coordination and stays in the `flow/` triggers (`Foreground` keeps the title
 * current; `Provision` fills a name a scan couldn't fetch while offline) over a `compose/`-built
 * `EventDirectory` effect; this feature owns the decision of whether the fetched value is persisted.
 *
 * Seated in `feature/membership` because the membership config is this feature's durable state
 * (one-writer: join/provision saves it, leave clears it, and this refresh rewrites it whole).
 */
class EventName(
    private val configSource: ConfigSource,
    private val store: ConfigStore,
) {

    /**
     * Store [fetched] as the joined event's name — iff it resolved at all (`null` is the sealed
     * no-result of a best-effort fetch — offline / 404 / parse — and stores nothing; part of this
     * rule since the migration finale, so the flows' fetch-then-store is a single straight-line
     * step), [eventId] is still the configured event (a fetch that resolves after a switch/leave
     * must not resurrect the old membership), and the name actually changed (an unchanged name
     * saves nothing).
     *
     * The save is the **whole** current config with only `name` replaced (`copy(name = fetched)`) —
     * a name refresh must never clobber the persisted cutoff (capability `photo-selection-policy`)
     * or any other membership field.
     */
    suspend fun storeEventNameIfChanged(eventId: String, fetched: String?) {
        if (fetched == null) return
        val current = configSource.config.value
        if (current?.eventId == eventId && current.name != fetched) {
            store.save(current.copy(name = fetched))
        }
    }

    /**
     * Whether the membership's title still needs a directory fetch (capability `join-event`): a
     * scan-path config arrives **nameless** (the QR payload carries only the event id; a
     * create/interactive-join carries its loaded name), and only then is the network fetch worth
     * firing. The rule moved here from the Provision flow's `if` at the migration finale — the
     * flow now switches on this sealed answer (the transcriber grammar's sealed-result form).
     */
    fun fetchNeed(name: String): TitleNeed = if (name.isEmpty()) TitleNeed.MISSING else TitleNeed.PRESENT
}

/** The sealed answer of [EventName.fetchNeed]: does this membership's title need a fetch? */
enum class TitleNeed { MISSING, PRESENT }

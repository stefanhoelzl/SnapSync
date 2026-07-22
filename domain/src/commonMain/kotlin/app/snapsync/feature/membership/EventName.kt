package app.snapsync.feature.membership

import app.snapsync.model.JoinLoad
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
     * Fold a freshly [fetched] event-details result into the persisted membership — iff it resolved at
     * all (`null` is the sealed no-result of a best-effort fetch — offline / 404 / parse — and stores
     * nothing) and [eventId] is still the configured event (a fetch that resolves after a switch/leave
     * must not resurrect the old membership). Two rewrites ride together, in **one** whole-config save:
     * - **name refresh**: persist a changed event name (an unchanged one saves nothing);
     * - **window backfill** (capability `event-rejoin-reconciliation`): a membership persisted before the
     *   event window existed carries a `null` `endsAt`; fill `endsAt` and the ceiling `maxPhotoDate` from
     *   the fetched details, but only when absent, so a chosen ceiling is never overwritten.
     *
     * The save is the **whole** current config with only those fields replaced — it must never clobber
     * the persisted cutoff (capability `photo-selection-policy`) or any other membership field; and doing
     * both edits in one save is what stops the name refresh and the backfill from losing each other.
     */
    suspend fun storeRefreshedDetails(eventId: String, fetched: JoinLoad.Found?) {
        if (fetched == null) return
        val current = configSource.config.value ?: return
        if (current.eventId != eventId) return
        var next = current
        // Name refresh: persist a changed name (an unchanged one saves nothing).
        if (current.name != fetched.name) next = next.copy(name = fetched.name)
        // Window backfill (capability `event-rejoin-reconciliation`): a membership persisted before the
        // event window existed carries a `null` `endsAt`; fill it — and its ceiling — from the freshly
        // fetched details, so a legacy member gains the same range a new join has. Only when ABSENT, so a
        // chosen ceiling is never overwritten. Done in the SAME `save` as the name so the two rewrites of
        // the whole config cannot lose each other's field.
        if (current.endsAt == null) {
            next = next.copy(endsAt = fetched.endsAt, maxPhotoDate = current.maxPhotoDate ?: fetched.endsAt)
        }
        if (next != current) store.save(next)
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

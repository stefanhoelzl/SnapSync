package app.snapsync.model

import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.request.RequestId

/**
 * The **user-tap command bundle** (`docs/architecture.md`, "Commands cross one door"): the
 * commands the status screen can fire, so every user tap crosses the same door the OS-callback
 * triggers do. Seated in `model/`: the bundle is pure vocabulary — an interface, like a port, so it carries
 * no code of its own — and `model/` is the one zone both `compose/` (which
 * builds the live instance) and `:domain:presentation` (which receives it by constructor) may name;
 * presentation has no edge to the entry points, and they may keep importing `model/`. Live instances are
 * still **built and decorated only in `compose/`** (`AppCore.userCommands`) — presentation never references a
 * feature command (or an entry point) directly, which is what the presentation gate enforces.
 *
 * Reads do NOT ride here: presentation observes feature read-model StateFlows directly (config,
 * permission, sync status, creation status, download progress), and the join gate's details fetch is the query
 * layer's `JoinDetailsQuery` — it returns a value the gate reduces on, so it is a query, not a command.
 *
 * No command has a default body (law "Function-typed parameters have no defaults in production",
 * `docs/architecture.md`): an implementation states every command, so one it forgets does not compile — an
 * inert default is how a hand-built bundle ships a "Choose more photos" button that does nothing.
 *
 * - [leave] — leave the joined event. Returns at once: the membership turning "not joined" is its outcome, and staying
 *   joined is a leave that could not be written.
 * - [create] — mint a new event with a name and canonical UTC date **range** (`startsAt`, `endsAt`) — and, for an
 *   event created after its end, the day guests may join until (`openUntil`, `null` otherwise) — then
 *   route it into the join gate. Fire-and-forget; outcomes arrive via
 *   the creation status read-model.
 * - [commitJoin] — join the event the member confirmed ([JoinChoice]). Returns at once with the request's id; what it came
 *   to is answered on the join status under that id.
 * - [share] — hand the invite URL to the platform share surface, titled with the event's name (fire-and-forget,
 *   `UiState` unaffected).
 * - [requestAccess] — raise the system photo-access dialog: returns
 *   nothing and cannot suspend — the grant arrives only via the permission read-model.
 * - [openLink] — hand a URL to the platform to open outside the app. Its ONE caller is the
 *   update-required screen's App Store button, whose remedy is by
 *   definition not in this app. The URL is passed in rather than known here, because the screen's
 *   contract is that a build carrying no store URL renders no button — a command that knew the URL
 *   could not express that.
 * - [openSettings] — open the app's system Settings page (the `DENIED` affordance). Distinct from
 *   [reconfigure], which edits this *membership's* settings, not the iOS system settings page.
 * - [choosePhotos] — present the platform's limited-library picker: the joined layer's
 *   "Choose more photos" affordance under a partial
 *   grant. Fire-and-forget; the resulting selection change arrives via the selection-change seam.
 * - [reconfigure] — change the joined membership's share in place ([ShareChoice]: range, direction, album) without
 *   leaving. Returns at once with the request's id; what it came to is answered on the settings status.
 * - [rename] — rename the joined event for **every** member. [eventId] is the event the heading affordance was opened
 *   for; nothing happens if the membership no longer is. Returns at once with the request's id; what it came to is
 *   answered on the rename status, and the new name lands via the membership.
 * - [switch] — another event's invite has loaded while joined: whether leaving the joined one may happen without
 *   asking. Returns at once with the request's id; the verdict is answered on the switch status.
 * - [sendDiagnostics] — send this device's diagnostic dump to the operator's reporting channel, or keep it on
 *   the device on a build that reports nowhere, fired from the report sheet
 *   once the user has written what went wrong, and answering what became of it ([ReportOutcome]) so the app
 *   can say so. `note` is that description, already trimmed and length-bounded by the sheet — it
 *   titles the report, so two reports about different problems arrive as different issues. `context` is
 *   [ReportContext]: an opaque label for the surface it was sent from (the domain enumerates no screens — the
 *   only way a screen-local surface, which touches no port, reaches a report), and what that surface showed.
 *   Present on every build: where the report goes is `UiState.reportDestination`, which the sheet states, so
 *   the affordance never suggests a destination the build does not have.
 * - [restoreKey] — keep the key a reopened invite of the joined event carries, when this device lost it
 *   (reopening the current event's invite changes nothing else) — only the event's own key, only while it is lost —
 *   and sharing and receiving resume at once. Answered on the restore status under the returned id.
 */
interface UserCommands {
    fun leave()

    suspend fun create(name: String, startsAt: EventStart, endsAt: EventEnd, openUntil: OpenUntil?)

    fun commitJoin(choice: JoinChoice): RequestId

    suspend fun share(url: String, title: String)

    suspend fun requestAccess()

    suspend fun openSettings()

    suspend fun openLink(url: String)

    suspend fun choosePhotos()

    fun reconfigure(choice: ShareChoice): RequestId

    fun rename(eventId: EventId, name: String): RequestId

    fun switch(invite: InviteFact): RequestId

    suspend fun sendDiagnostics(note: String, context: ReportContext): ReportOutcome

    /** The device's mobile-data choice, from the app menu: answers whether it was saved. */
    suspend fun setMobileData(on: Boolean): Boolean

    fun restoreKey(key: EventKey): RequestId
}

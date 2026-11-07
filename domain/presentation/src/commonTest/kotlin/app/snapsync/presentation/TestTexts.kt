package app.snapsync.presentation

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.invite.InviteText
import app.snapsync.domain.invite.MintsInviteText
import app.snapsync.domain.invite.RendersInvite
import app.snapsync.domain.time.Timestamp
import app.snapsync.ui.state.MintsShownInvite
import app.snapsync.ui.state.ShownInvite
import app.snapsync.ui.state.SwitchTo
import kotlin.time.Instant

/** The instant [text] names (ISO-8601), as the screen's facts carry it. */
internal fun timestamp(text: String): Timestamp = Timestamp.of(Instant.parse(text))

/** [this] instant as a test states it: ISO-8601, to the second. */
internal fun Timestamp.text(): String = at.toString()

/** The invite to [event] a link opened, already read — carrying [key] for an encrypted event. */
internal fun invited(
    event: EventId,
    key: EventKey? = null,
): InviteLinkVerdict = InviteLinkVerdict.Invite(InviteLink(event, key))

/** The invite text a test hands the screen for [event]: the screen shows and shares it as given. */
@OptIn(MintsInviteText::class)
internal fun inviteText(event: EventId, key: EventKey? = null): InviteText = InviteText(
    "invite:${event.value}" + if (key == null) "" else "#key-${key.hashCode()}",
)

/** A switch flipped to [on], as a test states it. */
internal fun switchTo(on: Boolean): SwitchTo = if (on) SwitchTo.ON else SwitchTo.OFF

/** The invite the screen shows for [event], as [inviteText] hands it over. */
@OptIn(MintsShownInvite::class, RendersInvite::class)
internal fun shownInvite(event: EventId, key: EventKey? = null): ShownInvite = ShownInvite(inviteText(event, key).raw)

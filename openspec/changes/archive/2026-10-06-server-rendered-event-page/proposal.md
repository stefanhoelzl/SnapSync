# Proposal

## Why

When a member shares an event's invite link, the preview in the chat (iMessage, WhatsApp, Signal, Telegram, …) and in
their own share sheet says "SnapSync — event photos", never the event's name. The event page is one constant file for
every event, and the event's identifier rides in the link's fragment. Previewers run no script and never send the
fragment, so nothing that renders a preview can learn which event the link names. The fragment rule was bought to keep
the identifier off every server. That is a promise worth less than a link that says what it is: the identifier already
sits in every chat server that carries the message. This change gives the rule up for a server-rendered event page.

This is **phase 1 of 2**. Installed apps reject any invite that is not the fragment form, so phase 1 makes every
version from now on *accept* the new link while the app keeps *sharing* the old one. Phase 2 is a separate change made
once phase 1 has reached users: the app shares and QR-encodes the new link, and the minimum app version rises.

## What Changes

- **A second invite form, `https://snapsync.stho.net/join/<event identifier>`:** opens the app on its join screen, and
  in a browser opens a page the server renders for that event. The fragment form stays openable forever. An optional
  fragment on the new form carries the development-only join hints, honoured only by development builds as today.
  Phase 1 still shares and QR-encodes the fragment form.
- **The event page is rendered per event:** the server renders the event's name, its dates in the host's own calendar,
  whether it has not started, is happening now or has ended, how many members it has, and after the end how many have
  finished sharing and until when its photos stay available. A link preview carries the same name and facts, and its
  image is the app icon, never a photo. An invalid or expired link previews as such. The photo count and the zip
  download stay in the browser, as today.
- **Fragment links opened in a browser move to the per-event page.** That sends the event's identifier to SnapSync's
  own service when the page loads.
- **BREAKING (privacy promise):** "the event's identity stays off the wire" and "the invite's secret never reaches a
  web server" are given up. Opening an invite in a browser now sends the event's identity to SnapSync's own service,
  still never to a third party (the Google Play button excepted, as today), and the page tells the visitor's browser
  not to pass the address on to other sites. The Privacy Policy is rewritten to say so plainly: the link holds the
  event's identifier, so treat it like a key.
- **The event remembers the host's time zone** at creation, so its dates show as the host chose them. Events created
  earlier show their dates in UTC.
- **The share sheet names the event:** sharing the invite from the joined screen shows the event's name as the
  share's title on iPhone and Android.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `join-event`: the invite format gains the path form, still openable forever beside the fragment form. "The invite's
  secret never reaches a web server" is removed; what a browser may send is now `privacy-security`'s.
- `event-site`: the page names the event's dates, status and members as well, renders without a script, previews in
  chats, and a fragment link opened in a browser moves to the event's page.
- `privacy-security`: "the event's identity stays off the wire" becomes "the event's identity goes only to SnapSync's
  own service"; "the invite link is the key" names what the key shows.
- `manage-membership`: the share action's sheet carries the event's name.

## Impact

- **api/:** a new `GET /join/:eventId` route renders the built page template with the event's facts (no cache); a
  `zone` column (migration 0009) and an optional `zone` on `POST /events`; the AASA claims `/join/*`.
- **site/:** `join.astro` gains placeholders for the server-filled facts; the island reads the identifier from the path,
  redirects a fragment link, and fetches only the photo list. The Privacy Policy text changes.
- **App:** the `EventLink` codec decodes both forms (the encoder is unchanged); the Android intent filter adds
  `/join/` as a prefix; create sends the device's zone; `SystemUi.share` takes a title (LinkPresentation metadata on
  iOS, `EXTRA_TITLE` on Android).
- **Compatibility:** apps older than phase 1 still receive only fragment links. Phase 2 (deferred) switches the
  shared form and raises the app-version gate.

# Design

## Context

Encryption's first phase shipped in v0.5 (commits `24af063d3`, `c4757f45a`, `a001bfb06`, `564157824`, `06f63bb14`):
the encrypted file format, the api refusing unsealed bytes for an event with a `key_id`, joining / uploading /
receiving an encrypted event on both platforms, and the event page opening one. Creation sits behind
`DevControls.encryptsNewEvents()`, inert `false` in production; its KDoc already says the enabling release *replaces*
that read rather than flipping it.

Facts that shape this phase:

- The api deploys on every merge to `main`; an app merge reaches only internal TestFlight / Play internal. Users get
  0.6 at its promote. This change merges before then, after the `cleanups` branch (which adds `PENDING_CLEANUPS.md`).
- A 0.4 app rejects the path form outright (its decoder knows only `/join#`), so it shows a damaged invite, not
  "update". `MIN_APP_VERSION` is `0.4`.
- `EventKeys.current()` already separates `null` (the slot is **Absent**) from a throw (`SecureStoreUnavailable`, a
  locked device). `UploadSealing` withholds in both cases; `DownloadOpening` fails the open and **re-downloads**, so a
  device with no key fetches the same bytes forever.
- The join gate's same-event rung (`StatusContainerHost.onOpenUrl`, "already joined") ignores the link entirely.
- The event page's Play referrer is rebuilt from the event id alone (`site/src/lib/invite.ts` `inviteFor`), and the
  app's referrer decoder prepends the fragment-form prefix, whose strict JSON has no key field.
- iOS keeps the key under `kSecAttrAccessibleAfterFirstUnlock` (not `…ThisDeviceOnly`), so an iCloud or encrypted
  local backup restores it; an unencrypted local backup does not, while the App-Group config does come back. Android
  backs nothing up (`allowBackup=false`); its loss is a Keystore key invalidated under `AndroidSecureStore`, which then
  reads Absent.

## Goals / Non-Goals

**Goals:**
- Production creates only encrypted events; the rig keeps plain creation reachable for tests.
- A lost key is an observable, recoverable state that stops all transfers instead of looping downloads.
- Android's install-from-the-page path keeps working for encrypted events.

**Non-Goals:**
- No api change and no backend deploy: `MIN_APP_VERSION` 0.5 is a separate step before the 0.6 promote.
- The site / store-listing privacy claim — also a 0.6-promote step, recorded in `PENDING_CLEANUPS.md`.
- Retiring plain events (api refusing keyless creates, deleting the app's plain paths) — a later change, once the
  minimum is 0.6 and no plain event is open.
- Changing the iOS PhotoKit extension's one-file-key path: PhotoKit uploads the asset bytes itself, so the edge must
  seal. It stays, and the privacy text states it.

## Decisions

### D1 — `DevControls.encryptsNewEvents()` becomes `createsPlainEvents()`, inert `false`

The production adapter (`InertDevControls`) keeps answering the inert value, and the inert value now means
"encrypt". `EventKeyMinting.forNewEvent()` mints unless `createsPlainEvents()` answers `true`. Alternatives: flip
`InertDevControls` to `true` — rejected, it leaves a production read whose name says "dev control" deciding a product
rule, and a rig build that forgets to set it would diverge from production the wrong way round; drop the control
entirely — rejected, plain events still exist until the retirement change and tests must reach them. The control
is deleted by the retirement change.

The rig verb stays `device/encrypt-new-events` (default `on=true`; `on=false` creates plain) so the vocabulary,
`RigVocabulary` and the `rig-channel` skill change only their default and wording. The mocks' and `RigDevControls`'
default becomes encrypted, so a JVM/rig test runs the production default.

### D2 — Key presence is a read-model: `EventKeys.presenceOf`, read by the screen through `EventKeyReads`

A `StateFlow<KeyPresence>` over the joined config: `NotNeeded` (plain or not joined), `Held`, `Unknown` (the store
threw — a locked device), `Lost` (the event has a `keyId` and the slot reads Absent, or holds a key whose id is not
the event's). Re-read on every membership change, on every foreground, and after a key is kept. Only `Lost` is
shown; `Unknown` is treated as "not lost" so a locked background wake never raises the line (the spec's locked-phone
scenario). Alternative: derive it from the transfers' withhold reasons — rejected, it would only appear after a cycle
ran and could not tell "locked" from "lost" reliably across two processes.

### D3 — A lost key stops transfers at their admission, not at their outcome

Both directions stop before any work starts, in both processes:

- **Uploads** — the upload cycle's gate refuses to run while presence is `Lost`: no walk, no export, no seal attempt,
  no manifest publish, no job — for the app's uploader and the PhotoKit extension alike (the extension reads the
  same shared slot). `UploadSealing`'s withhold stays as the backstop, not the mechanism: today a lost key still
  walks, exports and stages each candidate before refusing it, every cycle. Jobs handed to the OS before the loss
  were sealed with the key and finish as they are.
- **Downloads** — no download is enqueued while presence is `Lost` (`DownloadArm.keyHeld`), and `DownloadOpening`
  stops counting a "no event key is kept" failure toward its report (it is the state, not a corrupt file).

Nothing already staged is discarded; both directions resume when the key returns. The status line's counts stay
frozen because nothing completes. `Unknown` (a locked device) keeps today's behaviour: the cycle runs and the seal
withholds, so a locked background wake is not mistaken for a loss.

### D4 — The same-event rung restores the key

In `onOpenUrl`, before "already joined" ignores the link: if the current event's presence is `Lost` and the link
carries a key whose id is the event's `keyId`, call a new `UserCommands.restoreEventKey(linkKey)`, which
`EventKeys.keep`s it and re-reads presence; then kick a foreground cycle so sharing resumes at once. Any other
same-event link stays the logged no-op. Alternative: route it through the join screen — rejected, a rejoin
re-enrolls and could change settings; the spec says nothing changes but the key.

### D5 — Status line priority: lost key right after "Not sharing or receiving"

A lost key blocks every direction and is fixed by something outside the app (another member's invite), so it
outranks access and network; "Not sharing or receiving" stays above it because with both directions off nothing
needs the key. New `ScreenMessage`/status variant, EN + DE strings, not tappable.

### D5b — No invite without its key

`Layer.Joined.inviteUrl` becomes nullable: `null` for an encrypted event whose key this device cannot read — lost, or
a phone locked since it started — so Share and QR are not offered and a shown QR closes (`maskedFor`). The invite key
is re-read with the presence (every foreground, after a restore), so the invite returns the moment the key is
readable. Alternative: keep offering a keyless invite — rejected, the recipient would only meet the incomplete-invite
wall.

### D6 — The Play referrer carries `k`

`inviteFor` on the page takes the key the page itself verified against the event's `keyId` (`event-key.ts`) and the
referrer becomes `v=3&d=<payload>&k=<key>`, built from those three parts alone. The app's referrer decoder
(`inviteLinkFromInstallReferrer`) accepts an optional `k` beside `v`/`d` and re-encodes the payload with its key —
which yields the keyed path form. A referrer from a page opened without a valid key carries no `k`. A 0.5 app reads a
referrer with `k` as malformed and opens as an ordinary install; harmless, since no production event is encrypted
before 0.6 ships.

### D7 — Privacy Policy now, marketing claim at the promote

The policy is factual and versioned in its own words ("events created with SnapSync 0.6 or later"), so it can deploy
with this merge. The site's "Private by design" card and the store listing gain a concrete line only at the 0.6
promote (`PENDING_CLEANUPS.md`); the user accepted that the line is not true of plain events 0.5 still creates until
the retirement change. `metadata/messaging.md` keeps "encrypted" out of marketing copy.

### D8 — The integration suite runs the production default

The JVM world learns an encrypted event's key the way no phone gives it away: `EventKeyLedger` (in
`:adapter:generic:mock`, wired by `JvmMocks`, since the `:app:jvm` shell may hold no decision)
records every 32-byte draw of the process's `Crypto` and the `keyId` each keyed `createEvent` answered with an event
id, so an event's key is the drawn key with that id. Over it: the mocked download session seals the bytes it leaves
under the event's key (`DownloadSessionOperator.stored`, set through `EventKeyLedger.sealsDownloadsOf` over the app's own `FileCipher`) as the uploading member would have; the
JVM-only rig verb `device/invite?event=` answers the event's whole invite, which `Rig.inviteLink` uses; and
`device/event-key/lose` drops the key slot for the lost-key tests. The app host refuses both verbs (a phone's key
leaves only inside its invite). Rejected: opting each test into plain events — it would leave the suite running a
default production no longer has. Not done: the mocked uploads still carry placeholder bytes and the backend mock does
not refuse an unsealed body; sealing is held by `EncryptedTransfersTest` and the real api's own tests.

## Risks / Trade-offs

- [0.6 promoted before the minimum is raised] → 0.4 users see "damaged invite" for every new event. Mitigation: the
  bump and its SQL precondition (no 0.4 device in an open event) are the first entry of `PENDING_CLEANUPS.md`.
- [Flipping the rig default broke 58 of 216 integration tests: they open invites they build themselves, without the
  key, and the mocked downloads leave a fixed JPEG that does not open as an encrypted file] → the suite was made
  encrypted-aware rather than opted out (D8).
- [An unencrypted local backup restore leaves a joined device without its key] → it now shows the lost-key line
  instead of looping downloads; the invite restores it.
- [Google Play holds a whole invite] → the spec already grants Play the ability to see the event's photos through
  that button; the key makes that literal, only on the visitor's tap.
- [The PhotoKit extension path gives the service one photo's key] → stated in the policy and the spec; the
  marketing line is the stored-only claim, true on every path.

## Migration Plan

1. Merge after `cleanups`; add the two `PENDING_CLEANUPS.md` entries (min 0.5 + SQL check; site/listing claim).
2. Internal TestFlight / Play internal create encrypted events against the production api, which already accepts
   them (v0.5's api change).
3. Before the 0.6 promote: run the SQL check, merge the `MIN_APP_VERSION` 0.5 bump, then the claim, then promote.

Rollback: revert the `createsPlainEvents` read to answer `true` in production; encrypted events already created keep
working on every 0.5+ build.

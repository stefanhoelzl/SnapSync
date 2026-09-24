## MODIFIED Requirements

### Requirement: Silent-push receive seam

`:domain`'s `ports/` SHALL define the `PushReceiver` seam invoked when the app receives a silent
(`content-available`) push, carrying the pushed **`eventId`** (delivered as the push payload's
top-level `eventId` key, capability `apns-push-sender`). The seam SHALL be **asynchronous**
(suspending / completion-shaped) so the app-shell can **await** the push's **own work** — the download
arm's union read, plan and download enqueue — **before** signalling the OS background-fetch completion
handler, keeping the app alive through the push's execution window rather than risking suspension before
any transfer is enqueued. The handler SHALL then be released **as soon as that own work is done**, and
SHALL NOT be held for anything else (capability `ios-app-shell`, which owns the handler, its release and the
tail that follows it). The wired implementation SHALL trigger download discovery
`reconcile(eventId)` (capability `photo-download`) **guarded** on the pushed `eventId` equalling the
device's **active event** (from the config seam): a push whose `eventId` is not the active event —
**including when no event is configured** — SHALL be a **no-op** (no reconcile). The guard exists
because leave is local-only (capability `leave-event`): a left event's backend membership persists and
keeps pushing this device, so an unguarded reconcile would silently re-pull a left event's new photos.
The guard + reconcile logic SHALL live in a **tested** feature (`DownloadPushReceiver`, `:domain`
`feature/download`, exercised on JVM and `iosSimulatorArm64`), never parked in the untested app shell.
Importing the staged downloads is **not** the push's own work: it is the first unit of the process-wide
tail (capability `ios-app-shell`), which runs after the handler is released.

The `flow/SilentPush` trigger (`:domain` `flow/`, built in `compose/`; it absorbed the former
`FanOutPushReceiver`) SHALL run the push's own work and **only** that: the download arm's receiver. The
upload arm SHALL NOT be a receiver of the push, and no upload cycle SHALL run as push work. A push is still
news to the upload arm — another member's upload completing is when this device most likely has photos of
its own to contribute (capability `upload-completion-notify`) — but the upload work reaches the push's wake
through the **tail** that follows the released handler: the upload top-up (re-create retry-spent failures,
enqueue the ledger's known rows) and the discovery walk with its manifest publish (capability
`ios-app-shell`; `ios-url-session-upload`). Running the upload cycle as push work held the handler behind
a library walk — measured, a push that waited 22.5 s behind another cycle's walk — for work the push is not
about (decision record: `changes/own-work-per-wake`, D1).

The upload arm's two push guards SHALL be carried into the tail rather than dropped with the receiver:

- **the active-event guard.** The push's wake SHALL join the tail only when the pushed `eventId` is the
  device's active event, read from the membership the flow has just re-read; a push for any other event —
  a locally-left event included — or with no event configured, or while the membership is unreadable,
  SHALL cause no upload work. This preserves exactly what the upload receiver refused. The tail itself
  SHALL take no `eventId`: every unit acts on the device's active membership read fresh from the config
  seam, so no push can name the event the tail works on. The decision SHALL live in tested `:domain` code,
  never in the shell.
- **the limited-grant read-discipline guard.** It SHALL be enforced by the tail's upload units themselves
  — the mechanism — and not at the push (capability `limited-photo-access`, "The read discipline is
  enforced at the mechanism, not at the trigger fan-out"): the discovery walk SHALL run only under a
  photo grant of exactly `GRANTED`, read fresh when the unit starts; the top-up resolves its rows through
  the process's discovery binding, which under a partial grant is the in-memory selection snapshot (no
  library read) and is withheld while that snapshot is unread. Under a partial grant a push's tail
  therefore reaches ① (import) and ② (the top-up, from the snapshot) and never ③, and no autonomous
  library read follows the push. The direction gate (capability `upload-lifecycle`) stays where it
  is, in the cycle's entry decision, orthogonal to both.

The flow SHALL take the OS payload **whole** (migration step 12, the
transcriber law): the app-shell wiring forwards the raw `userInfo` dictionary and the completion
handler; the `eventId` extraction is the tested `model/` payload codec (`pushEventId`), applied
inside the flow — a payload with no usable `eventId` runs no receiver and joins no tail, and the
completion handler is released either way. Before running the receiver, the flow SHALL re-read the
persisted membership into the config StateFlow (the trigger-time `reloadConfig` re-read — see
`ios-app-shell`), so the active-event guards read current state.

#### Scenario: A push for the active event reconciles

- **WHEN** a silent push carrying `eventId` = the device's active event is delivered and routed to
  `PushReceiver`
- **THEN** the receiver runs `reconcile(eventId)` (discover foreign assets, plan and enqueue downloads), and
  the staged assets are imported by the tail that follows

#### Scenario: A push for a non-active or left event is a no-op

- **WHEN** the pushed `eventId` differs from the active event, or no event is configured
- **THEN** the receiver performs no reconcile (a left event's persisting membership cannot re-pull
  photos), and the wake joins no tail, so no upload work runs either

#### Scenario: A payload without an eventId wakes no arm

- **WHEN** a silent push arrives whose `userInfo` carries no usable top-level `eventId` string
- **THEN** the flow runs no receiver and joins no tail, logs the miss, and the OS completion handler is
  still released

#### Scenario: The OS handler is released after the push's own work

- **WHEN** a silent push for the active event is handled
- **THEN** the app-shell signals the OS background-fetch completion handler once the flow's own work
  (payload decode, membership re-read, attestation wake, and the download arm's union read, plan and
  enqueue) has completed — not after the tail — and the enqueued background transfers then continue on
  their own

#### Scenario: A push runs no upload cycle as its own work

- **WHEN** a silent push for the active event arrives on a contributing membership under a full grant
- **THEN** no upload cycle runs before the OS handler is released; the upload top-up and the discovery walk
  run afterwards, as units of the tail

#### Scenario: Under a partial grant the push's tail reads no library

- **WHEN** a silent push for the active event arrives while photo access is `LIMITED`
- **THEN** the download arm runs; the tail imports what is staged and tops up, resolving rows only from the
  selection snapshot; its discovery walk does not run, so no `PHAsset` fetch follows the push

#### Scenario: The receiver is replaceable

- **WHEN** a future change supplies a different `PushReceiver` implementation
- **THEN** it is invoked on receipt with the pushed `eventId` and no change to the app-shell receive
  wiring

### Requirement: Registration timing — launch, join, and rotation

The app SHALL **ask the OS for the APNs token at every app entry**: every cold start, whether the process
is launched into the foreground or the background, and every foreground entry. Apple's contract makes
asking the only way to stay current: the device token **can change** (Apple names restoring a backup,
moving to a new device and reinstalling the OS, and does not promise the list is complete), the app
learns a changed token **only by asking** — the OS delivers the current token in answer to the request and
does not push a rotation on its own — and Apple directs apps to request it on every launch rather than
cache it, describing the request as cheap.

Asking SHALL NOT imply registering. The app SHALL persist the **last-registered value** — the
(`token`, `env`, `deviceId`) triple of the last registration the backend **accepted** — and a token
delivered at an app entry SHALL be published only when that triple differs from the persisted value: a
rotated token, a changed environment, a changed device identity (a device reset), or no successful
registration yet. A failed publish SHALL NOT update the persisted value, so the next entry sends it again.
The publish SHALL happen from **whichever wake** learns of the change — a background cold start included, which
installs the registration subscription like a foreground launch does (capability `ios-app-shell`, "Push
registration is started by the shared composition") — never deferred to the next foreground.
Two triggers SHALL publish **unconditionally**, whatever the persisted value holds, and record it on
success:

- **on join** (when the device provisions an event), and
- **on a fresh credential** — whenever a new device credential is obtained: a first mint, a re-attestation,
  and **every periodic renewal** alike. `DeviceAttestation.tokenChanged` fires on each of them, a renewal
  included, and the registration SHALL follow it without distinguishing a renewal from a mint. A renewal
  therefore re-registers even when the (`token`, `env`, `deviceId`) triple is unchanged; this is accepted:
  it costs one publish per renewal, and it keeps the one path that heals a refused write unconditional.

Registration SHALL stay idempotent — re-registering the same token overwrites the device's stored
registration (last-write-wins at the endpoint) — which is what makes the two unconditional triggers and a
repeated publish safe. Idempotence SHALL NOT be the reason to publish at every launch: an unconditional
launch publish was measured on 102 of 108 process starts, 0.5–3 s each, most of them background wakes
whose time is the scarcest the app has (decision record: `changes/own-work-per-wake`, D12).

The registration write SHALL be treated as **refusable**. It requires an attestation record on the
backend, and answers `401` when there is none (capability `api-endpoints`); the app already recovers from
any `401` by obtaining a fresh credential, and obtaining one SHALL re-send the registration. Without that
retry the device would go unregistered until its next app entry re-sent the unrecorded triple — and a
device that receives no silent pushes gets fewer entries to re-send it from.

Registering on join exists to close a **warm-rejoin** window: a device can hold a backend record whose
push registration is absent, and would then receive no silent pushes until its next registration. Two things
produce that state — a device that re-attested after its record was collected (attestation records no push
token, so the recreated row's registration columns are empty), and one whose registration write was
refused. Registering on join restores it immediately.

The window is **narrower than it was**, and the reason is worth keeping: the scheduled cleanup no longer
collects a device's record while a token minted for it can still verify (capability
`scheduled-cleanup`). A device whose record is gone therefore cannot rejoin warm — it holds no usable
credential, so it must attest first, and attesting recreates the record. What remains is the absent
*registration*, not an absent record.

**Accepted residual.** A registration the backend loses for a reason no known path explains — neither a
collected record nor a refused write — is not healed by an unchanged launch any more: the persisted value
still matches, so nothing is sent. It heals at the next join, token rotation, or fresh credential. The known
loss paths are exactly the ones those triggers already heal, and a publish on every foreground cold start
as a healing net was declined for its cost (decision record: `changes/own-work-per-wake`, D12).

#### Scenario: The token is asked for at every app entry

- **WHEN** the app cold-starts, in the foreground or into the background, or enters the foreground
- **THEN** it asks the OS for the APNs token

#### Scenario: An unchanged token is not re-published

- **WHEN** the OS delivers a token at an app entry and (`token`, `env`, `deviceId`) equals the persisted
  last-registered value
- **THEN** `PushRegistration` publishes nothing

#### Scenario: A first or changed token is published and recorded

- **WHEN** the OS delivers a token at an app entry and no last-registered value is persisted, or the
  persisted triple differs
- **THEN** `PushRegistration` publishes it, and on success persists the new triple

#### Scenario: A token rotation learned in a background cold start is published from that wake

- **WHEN** the app is cold-started in the background and the OS delivers a token whose (`token`, `env`,
  `deviceId`) triple differs from the persisted value
- **THEN** `PushRegistration` publishes it from that wake, without waiting for a foreground launch

#### Scenario: A failed publish is re-sent at the next entry

- **WHEN** a publish made because the triple differed fails
- **THEN** the persisted value is left unchanged, so the next app entry publishes again

#### Scenario: Registration fires on join

- **WHEN** the device provisions (joins) an event and an APNs token is available
- **THEN** `PushRegistration` runs for that token, re-registering it with the backend even if the persisted
  value already matches

#### Scenario: Rotation re-registers

- **WHEN** the OS delivers a new (rotated) APNs token in answer to an app entry's request
- **THEN** it differs from the persisted value, and `PushRegistration` runs again with the new token,
  replacing the stored registration

#### Scenario: A refused registration is re-sent once a fresh credential is obtained

- **WHEN** the registration write is refused because the backend holds no attestation for the device
- **THEN** the app attests afresh and re-sends the registration, without waiting for the OS to deliver
  another APNs token or for the next app entry

#### Scenario: A periodic renewal re-registers

- **WHEN** the app renews its device credential on a wake, and the APNs token, environment and device
  identity are unchanged
- **THEN** `PushRegistration` publishes the registration, because a fresh credential publishes
  unconditionally, renewals included

#### Scenario: An unexplained backend loss waits for the next trigger

- **WHEN** the backend has lost the device's registration for no known reason and the app is launched
  with an unchanged token, environment and device identity
- **THEN** nothing is published at that launch, and the registration is restored at the next join, token
  rotation or fresh credential

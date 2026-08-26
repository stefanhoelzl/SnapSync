## MODIFIED Requirements

### Requirement: The autoJoin flag auto-confirms the gate
When a decoded event link carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap.

The `autoJoin` reading SHALL be reached **only after** the delivery has been established as one the gate
has not already acted on (capability `event-link`): a repeat of a link whose pending join is open, or
whose event is already the joined one, is ignored **whatever `autoJoin` says**. That ordering is the
whole of the protection, because this is the one path with no confirmation surface to absorb a second
delivery — everything else asks for a tap, and a tap happens once however many times the link arrived.
The platform does deliver the same link more than once (measured: twice on an iOS 18.7.9 cold launch
~130 ms apart, and twice on iOS 26.6 both while running and cold), so before that ordering an
`autoJoin` link provisioned once per delivery. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's **`startsAt`** — never an absent cutoff,
capability `photo-selection-policy`) unless the event link carries an explicit dev/test cutoff (see capability
`event-link`), in which case that value SHALL be used **subject to the floor**: the persisted cutoff
is `max(override, startsAt)`, so an event link's cutoff can raise a membership above the event's start but
never lower it below. SHALL use the **default** direction **Both** unless the event link carries an explicit
dev/test `direction` override (`both`/`upload`/`download`, capability `event-link`), in which case
that direction SHALL be used; and SHALL use the **default** album choice **off** unless the event link
carries an explicit dev/test `saveToAlbum` override (capability `event-link`), in which case that
value SHALL be used. This keeps the headless developer launch path working (it cannot tap a confirm
control) and lets it force a direction and album choice on device; to exercise date filtering against a
distant-past library, the developer SHALL create the event with an early `startsAt` (the create screen's
picker is unbounded) rather than relying on an unclamped override. Because the auto path has no
interactive surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather
than parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using startsAt as the cutoff, Both direction, and album off
- **WHEN** an event link with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `startsAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin honors an explicit dev/test cutoff above the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **later** than the event's `startsAt` and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin clamps an explicit dev/test cutoff below the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **earlier** than the event's `startsAt`
- **THEN** the auto-fired confirm provisions with `startsAt`, so a hostile QR cannot auto-join at a wider scope than the event itself allows

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** an event link with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly` (the producer is not enabled)

#### Scenario: autoJoin honors an explicit dev/test saveToAlbum override
- **WHEN** an event link with `autoJoin = true` carries `saveToAlbum = true` and its details load
- **THEN** the auto-fired confirm provisions with `saveToAlbum = true`, so a headless launch exercises album placement

#### Scenario: autoJoin still leaves an existing event
- **WHEN** an event link with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface

#### Scenario: A repeated autoJoin link provisions once
- **WHEN** the same event link carrying `autoJoin = true` is delivered twice through two different
  platform delivery hooks
- **THEN** the device provisions exactly once, the second delivery performing no enrollment, and the
  ignored repeat is recorded (capability `event-link`)

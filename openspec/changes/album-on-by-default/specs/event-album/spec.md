## MODIFIED Requirements

### Requirement: The album opt-in is a direction-independent join-surface affordance

The join surface (capability `join-event`) SHALL present the album opt-in as a **standalone** affordance,
nested under **neither** the Share switch nor the Receive switch. The album mirrors **both** the member's
own uploads **and** the foreign photos it downloads (see *Opt-in album mirroring per membership*), so
placing it under either switch would be a false statement about what feeds it. It SHALL rank **below**
both switches — it is a preference, not a consent decision — and SHALL **default on**: the album is the
only on-device statement that a set of photos belongs to this event, so a member who decides nothing
SHALL get the grouping the capability exists to provide. The default SHALL remain **declinable in one
tap** on the surface itself, and changeable afterwards through `reconfigure-membership` on that
capability's forward-only terms — the default decides what an untouched gate commits, never what a
membership is stuck with.

The **headless `autoJoin` path deliberately does NOT share this default**: it commits
`saveToAlbum = false` unless the event link carries an explicit override (capability `join-event`,
*The auto-confirmed join*). Its sibling defaults — the loaded `startsAt` as the cutoff, and direction
`Both` — do mirror this surface's seeds, so the divergence is stated here rather than inferred: a
headless dev/test launch does the minimal, side-effect-free thing, and the link's explicit
`saveToAlbum` override already exercises album placement without a tap.

The affordance SHALL render as a **checkbox** — not a switch — because the choice commits with Join (a
switch's "applies immediately" contract would be untrue here). Its off state SHALL draw an empty
affordance, and when the affordance is **dimmed** (not currently applicable) it SHALL remain present in
the accessibility tree as a **disabled** checkbox rather than dropping out of it, so assistive technology
still finds a control and reports it unavailable.

The affordance SHALL carry an explanatory **note that adaptively names exactly the feeds the current
switches produce**, so it can never claim a feed the membership does not have:

- both switches on → the photos the member shares **and** the photos they receive are collected;
- Share only → the photos the member shares are collected;
- Receive only → the photos the member receives are collected;
- neither switch on → nothing is shared or received, so nothing is collected.

The off-state note SHALL state that no album is created; because the affordance starts on, that note
SHALL be reachable only after a deliberate uncheck.

The chosen value SHALL cross to `JoinEvent` as `saveToAlbum` for **all** direction combinations (the
album is populated by whichever direction(s) sync).

#### Scenario: The album opt-in is standalone, not nested under a switch
- **WHEN** the join surface renders its loaded phase
- **THEN** the "Create an album" opt-in appears as its own row beneath both switches, nested under neither, and defaults on

#### Scenario: An untouched gate commits the album
- **WHEN** the user taps Join without touching the album row
- **THEN** `saveToAlbum = true` crosses to `JoinEvent`

#### Scenario: The default is declined in one tap
- **WHEN** the user unchecks the album row and taps Join
- **THEN** `saveToAlbum = false` crosses to `JoinEvent`, and the row states that no album is created

#### Scenario: The headless path does not inherit the surface default
- **WHEN** an `autoJoin` event link carrying no `saveToAlbum` override is auto-confirmed
- **THEN** the membership is committed with `saveToAlbum = false`, although the interactive surface would have defaulted it on

#### Scenario: The note names exactly the produced feeds
- **WHEN** both switches are on
- **THEN** the note states that photos the member shares **and** receives are collected; **WHEN** only
  Share is on it names only shared photos; **WHEN** only Receive is on it names only received photos;
  **WHEN** neither is on it states that nothing is collected

#### Scenario: The opt-in stays a present-but-disabled checkbox when dimmed
- **WHEN** the album opt-in is rendered in a dimmed (not-applicable) state
- **THEN** it remains in the accessibility tree as a disabled checkbox, reported unavailable rather than absent

#### Scenario: The album choice crosses in every direction
- **WHEN** the user opts into the album and taps Join for any switch combination
- **THEN** `saveToAlbum = true` crosses to `JoinEvent` alongside the derived direction and the cutoff

#### Scenario: An existing membership keeps its stored choice
- **WHEN** a membership persisted with `saveToAlbum = false` before this default changed is loaded
- **THEN** it stays `saveToAlbum = false` — no migration flips it — and the reconfigure surface seeds from the stored value

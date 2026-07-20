## ADDED Requirements

### Requirement: The album opt-in is a direction-independent join-surface affordance

The join surface (capability `join-event`) SHALL present the album opt-in as a **standalone** affordance,
nested under **neither** the Share switch nor the Receive switch. The album mirrors **both** the member's
own uploads **and** the foreign photos it downloads (see *Opt-in album mirroring per membership*), so
placing it under either switch would be a false statement about what feeds it. It SHALL rank **below**
both switches — it is a preference, not a consent decision — and SHALL **default off** (opt-in).

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

The chosen value SHALL cross to `JoinEvent` as `saveToAlbum` for **all** direction combinations (the
album is populated by whichever direction(s) sync).

#### Scenario: The album opt-in is standalone, not nested under a switch
- **WHEN** the join surface renders its loaded phase
- **THEN** the "Create an album" opt-in appears as its own row beneath both switches, nested under neither, and defaults off

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

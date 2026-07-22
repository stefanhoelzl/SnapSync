## ADDED Requirements

### Requirement: The join surface shows a live count of the photos that will be shared

The join surface's **loaded** phase SHALL present, within the Share section and beneath the Share switch,
a **live count** of how many of the device's own photos the currently-chosen cutoff would share to the
event, sourced from the shareable-count read-model (capability `join-share-count`). The count SHALL be
recomputed whenever the chosen cutoff changes (Now / Event start / Custom, or a Custom instant) and
whenever the Share switch is toggled, so the number always reflects the pending choice.

- When the count is a positive number `XX`, the row SHALL read `XX photos from your gallery will be
  shared`.
- When the count is **zero** — the legitimate result of the **Now** cutoff, or of no in-scope photos —
  the row SHALL read `0 photos from your gallery will be shared` together with a forward gloss `New photos
  you take will be shared as you go`, so a true zero does not read as a failure.
- While the count is (re)computing, the row SHALL show a brief `counting…` state in place of the number,
  resolving to the number when the computation settles.
- When the Share switch is **off**, the row SHALL be hidden (the Share section already hides the cutoff),
  since a member sharing nothing has no count.
- When the photo-access grant does not permit a count (`DENIED`, or unresolved `NOT_DETERMINED` —
  capability `join-share-count`), the row SHALL be omitted rather than showing a spinner that cannot
  resolve.

The count SHALL be rendered on the **switch-event** confirmation surface on the same terms, since a switch
establishes a fresh membership baseline for the new event.

The count is **decision-support on a decision surface** and does not change what confirming does: it
informs the cutoff choice, and confirming still crosses the chosen cutoff and derived direction to
`JoinEvent` unchanged.

#### Scenario: The loaded phase shows the count under the Share switch
- **WHEN** the `JoiningEvent` loaded phase renders with the Share switch on and the cutoff at Event start,
  and photo access permits a count
- **THEN** a row beneath the Share switch reads `XX photos from your gallery will be shared` for the
  count the chosen cutoff admits

#### Scenario: Changing the cutoff updates the count
- **WHEN** the user changes the cutoff choice from Event start to a Custom date reaching further back
- **THEN** the count recomputes and the row shows the new number (briefly showing `counting…` while it
  recomputes)

#### Scenario: A zero count carries the forward gloss
- **WHEN** the chosen cutoff is Now (or no photo is in scope), so the count is zero
- **THEN** the row reads `0 photos from your gallery will be shared` and `New photos you take will be
  shared as you go`

#### Scenario: Turning Share off hides the count
- **WHEN** the user turns the Share switch off
- **THEN** the count row is hidden along with the cutoff choices

#### Scenario: Without a usable photo grant no count is shown
- **WHEN** the loaded phase renders while photo access is `DENIED` or still unresolved
- **THEN** no count row is shown, and no library read is attempted for it

#### Scenario: The switch surface shows the count too
- **WHEN** the switch-event confirmation is presented for a different event and photo access permits a
  count
- **THEN** it renders the same `XX photos from your gallery will be shared` row for the new event's
  baseline

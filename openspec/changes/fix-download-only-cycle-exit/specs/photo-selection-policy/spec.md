## MODIFIED Requirements

### Requirement: Participation direction is a selection input on the policy

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date range and the origin exclusions. The policy answers *what does this member contribute?* — the
range bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

All three SHALL be carried to every policy consumer as one already-decided value, `SelectionPolicy`, defined
in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a — the only
zone visible to every consumer, `feature/upload` and `feature/status` being mutually blind). It is the
rules, not the inputs from which rules could be derived: a consumer receives the decision, never the
material to re-decide (see *The admitted set is a single derivation every consumer receives*).

- `SelectionPolicy.None` — the membership contributes nothing (`DownloadOnly`). It carries **no** rules and
  therefore **no** bounds, because a non-contributor has none to speak of.
- `SelectionPolicy.Admitting` — the membership contributes every asset **all** of its rules admit. It SHALL
  carry the capture-date **lower bound as a non-null field of the variant**, and SHALL derive its
  `CaptureAfter` rule from that field rather than accepting the rule as input. A contributing membership
  therefore always carries the lower bound **by construction**, not by convention.

`SelectionPolicy` SHALL be a **required** argument on every consumer, with **no default value**. This is a
privacy requirement, not an ergonomic one: there SHALL be no value, and no absent-argument fallback, under
which a membership admits the whole library. A default is prohibited in both polarities: a permissive
default admitting every capture date uploads the entire library from the beginning of time, and a
fail-closed default (`None`) makes a contributing member silently share nothing while the screen reads
"In sync" — the invisible failure this capability exists to prevent.

**Two states SHALL be unrepresentable**, not merely guarded against, because each was reachable in the type
and each cost a shipped defect:

- "contributes nothing, and here are the rules it is not using" — prevented by the two states being
  **distinct variants** rather than a rule list plus a boolean.
- "contributes, but with no capture-date lower bound" — prevented by the bound being a **non-null field**
  of the contributing variant. This closes the requirement *A lower bound `from` SHALL be required: a
  membership without one is not a representable state* at the type level rather than at each consumer.

Consequently the policy SHALL NOT expose an accessor that answers "what is the capture floor" with an
absent value. Such an accessor collapses "this membership contributes nothing" and "this policy has no
floor" into one answer whose two causes have opposite consequences, and it invites a consumer to branch on
the floor before checking the direction. A consumer needing the floor SHALL obtain it by exhausting the
sealed policy, so the non-contributing case is handled explicitly and the contributing case yields a
non-null bound.

The direction is a **per-membership** input, not a per-asset rule: it SHALL be applied **before** any
library walk, never as a rule evaluated within one. The walk costs one synchronous PhotoKit round-trip per
asset, so a non-contributor must never begin one to conclude it contributes nothing.

#### Scenario: A download-only membership contributes the empty set
- **WHEN** the membership's participation direction excludes upload
- **THEN** the selection policy admits no asset, regardless of any asset's capture date or origin

#### Scenario: The non-contributing case carries no bounds
- **WHEN** a membership contributes nothing
- **THEN** it is expressed as `SelectionPolicy.None`, which carries no rules and no capture-date bounds —
  the combination "contributes nothing, and here is the cutoff it is not using" cannot be constructed

#### Scenario: A contributing policy without a capture floor cannot be constructed
- **WHEN** any code attempts to express a contributing membership that admits every capture date
- **THEN** there is no such value: the contributing variant requires the lower bound, so the state is a
  compile error rather than a condition a consumer must detect at run time

#### Scenario: Reading the floor forces the non-contributing case to be handled
- **WHEN** a consumer needs the capture-date lower bound in order to bound a walk or a count
- **THEN** it obtains the bound by exhausting the sealed policy — receiving a non-null bound for a
  contributing membership, and handling the non-contributing membership on its own branch — and no
  accessor offers an absent floor that both cases could produce

#### Scenario: A non-contributor never walks the library
- **WHEN** the selection policy is applied for a `SelectionPolicy.None` membership
- **THEN** no library enumeration is performed — the empty result is reached before any per-asset walk begins

## MODIFIED Requirements

### Requirement: Library resource enumeration seam

The gallery domain SHALL define, in `:domain`'s `ports/` zone, a **single** library-read seam
(`CandidateSource`) that takes the membership's **selection policy** and returns either the library's
candidate assets or the statement that they cannot be determined. It SHALL be the only seam through which
the photo library is read for admission; there SHALL NOT be a second enumeration port layered over or
beneath it, and no read seam SHALL accept a flattened capture-date bound in place of the policy.

The seam's return SHALL **distinguish an unreadable library from an empty admitted set**, as a sealed
result — one case carrying the candidates, one stating that the admitted set cannot be determined — and
never by an empty collection standing in for both (spec `module-architecture`, "Absence is never silent").
Every implementation SHALL state its answer; none may reach the not-determinable case by defaulting.

The distinction is load-bearing rather than decorative, and for the same reason `null` and `0` are
distinguished in the count above: an empty candidate list is an admitted set of size zero, the status
projection settles when the synced count reaches the total, and a zero standing in for a library nobody
could read renders as **"everything shared"** on a device that has read nothing. Because the projection
publishes only a ready state, that frame cannot be retracted (capability `sync-status`).

The not-determinable case SHALL be named for its **consequence** — the admitted set cannot be stated right
now — and not for any single cause, so that every cause with that consequence reaches it. It SHALL absorb
at least: no photo-access grant, an unresolved grant, and a partial grant whose selection snapshot has not
yet been captured (capability `limited-photo-access`). Collapsing those causes into one answer is permitted
because no consumer distinguishes them — the status total goes un-counted and the join preview renders no
row for all of them — and that shared consequence is what this requirement states. A cause MAY be carried
for a device log as an opaque diagnostic, which no consumer may branch on.

A consumer SHALL NOT compensate for the collapse by consulting the photo-access grant itself before or
after calling the seam. Where candidates come from, and whether they can be produced at all, are both the
seam's answers.

Each candidate SHALL carry the asset's **neutral facts** (capability `photo-selection-policy` — the inputs
every rule decides on) and a means of obtaining that asset's **resources on demand**. Facts SHALL be
readable without a per-asset platform round-trip; resources SHALL cost one. Because admission is decidable
on facts alone, a consumer that needs only a count or the admitted asset set SHALL issue **no** resource
read, and a consumer that needs resources SHALL pay only for assets already admitted.

A resource SHALL carry `(filename, assetId, contentType, metadata)` — where `filename` is the upload key
(the reinstall-stable identity, `<assetId>-<kind>.<ext>`) and `assetId` groups a photo's resources. There is
**no** `version`: existence under the upload key is the proof of upload, so nothing compares content versions
and the ledger keeps no timestamp (capability `sync-ledger`).

This is the **single shared derivation** of those fields: the iOS background-upload producer's
full-enumeration path SHALL delegate to the same mapping, so the same `filename` is computed wherever
enumeration happens (the join seed and the producer agree byte-for-byte). The **app-side status consumer**
SHALL **also** consume this seam — but only to count the device's admitted assets, which is the status total
`N` (capability `sync-status`). It SHALL NOT derive an expected-filename set and SHALL NOT read the
per-device listing: own-device completeness is ledger-backed, and the status path issues no storage LIST.
What the shared seam guarantees is that the total counts exactly the assets the cycle would upload — so the
screen can reach 100% — not that two derivations of "complete" agree. Presentation SHALL keep consuming
counts only through the `feature/status` read-models, never the read seam directly (per "Module placement
keeps the seam off presentation").

The resource fan-out SHALL remain a **pure `commonMain` mapping**, the single site of that orchestration: it
SHALL normalize the `assetId` `'/'→'_'`, drop every resource whose raw type maps to no role (`resourceRole`
→ originals only), derive each kept resource's `filename` via `uploadKey`, and assemble the per-asset
manifest metadata. It SHALL be pure and platform-free and SHALL be unit-tested on JVM **and** the iOS
simulator; the platform adapter SHALL call it rather than reimplement any part of it, and SHALL hold no role
filter, key derivation, or normalization of its own. The MIME content type SHALL be resolved on the iOS side
(via `UTType.preferredMIMEType`, falling back to `application/octet-stream`) and carried as a raw fact —
`commonMain` SHALL NOT reimplement the UTI→MIME table.

The iOS implementation SHALL be PhotoKit-backed; `:adapter:generic:fake` SHALL provide the honest in-memory
implementation (state cell constructor-injected) so the mapping is driven on the JVM and the iOS simulator
without PhotoKit. An opaque platform handle SHALL cross `commonMain` uninterpreted (a JVM stand-in is
valid), exactly as `Resource.data` does. Both of those implementations read what they are given and always
have an answer, so both SHALL report the readable case; the not-determinable case arises where the grant and
the selection snapshot are known, which is the composition.

The PhotoKit implementation SHALL derive its `PHFetchOptions` predicate by **translating the policy's rules**
(capability `photo-selection-policy`), not from a caller-supplied bound. The predicate is an **optimization
only**: the authoritative in-memory admission runs over whatever the fetch returns, so the predicate MAY
return a superset of the admitted assets but MUST NOT return a subset. Where the predicate's evaluation
could disagree with the authoritative decision at a boundary, the predicate SHALL be **widened**, never
narrowed. The capture-date lower bound SHALL always be pushed into the predicate, because an unbounded walk
is watchdog-killed before the authoritative filter runs.

Three constraints on that predicate are **device-verified facts about PhotoKit**, not preferences, and an
implementation SHALL observe them:

- A media-subtype exclusion SHALL be written `NOT ((mediaSubtypes & N) != 0)`. The form
  `(mediaSubtypes & N) == 0` returns **zero rows** — it does not raise — even with the documented plural
  `mediaSubtypes` key. Shipping it would starve the walk of every asset.
- The predicate SHALL NOT contain **arithmetic** (for example `pixelWidth * pixelHeight`); it raises an
  uncatchable `NSException` and aborts the process. A resolution floor SHALL therefore be expressed in the
  predicate only as a **bounding box**, with the authoritative area comparison in `commonMain`.
- The predicate SHALL NOT reference `hasAdjustments`; it is not a supported key and likewise aborts the
  process.

A change feed reports what *changed*, not what is in *scope*: an iCloud sync or a bulk import surfaces
thousands of out-of-scope assets at once. An implementation reading a change feed SHALL therefore reject an
out-of-scope asset **before** reading its resources, using only the asset's own facts.

#### Scenario: One seam, taking the policy

- **WHEN** any consumer reads the library for admission
- **THEN** it calls the single candidate source with the membership's selection policy — no consumer
  flattens the policy to a capture-date bound, and no second enumeration port exists

#### Scenario: An unreadable library is not an empty admitted set

- **WHEN** the photo library cannot be read for admission — no grant, an unresolved grant, or a partial
  grant whose selection snapshot has not been captured
- **THEN** the seam reports the not-determinable case, and a consumer can distinguish it from a membership
  whose policy admits none of the assets it did read

#### Scenario: An empty admitted set is still an answer

- **WHEN** the library is readable and the policy admits none of its assets
- **THEN** the seam reports the readable case carrying no candidates, and the consumer treats it as a
  counted zero

#### Scenario: No consumer re-asks the grant

- **WHEN** the status total or the join preview resolves its answer
- **THEN** it reads the seam's result alone, and consults no photo-access grant of its own to decide
  whether an answer was available

#### Scenario: A count pays no resource read

- **WHEN** the status total or the join preview resolves a count
- **THEN** the candidate source returns facts-carrying candidates and no per-asset resource round-trip is
  issued

#### Scenario: Resources are fetched only for admitted assets

- **WHEN** a consumer needs the resources of the admitted set
- **THEN** the per-asset resource read is issued only for assets the policy admitted, never for one it
  excluded

#### Scenario: Enumeration yields per-resource identity

- **WHEN** a consumer resolves the resources of a library's admitted photos
- **THEN** each resource carries the upload key as its `filename` and the normalized `assetId` grouping its
  photo's resources, derived by the shared pure mapping

#### Scenario: The platform predicate cannot change the admitted set

- **WHEN** the platform translates the policy's rules into a native predicate and the predicate returns a
  superset of the admitted assets
- **THEN** the authoritative in-memory admission still excludes every non-admitted asset, so the result is
  identical to an unnarrowed fetch

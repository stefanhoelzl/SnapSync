## RENAMED Requirements

- FROM: `### Requirement: Join reconciliation seeds already-stored photos as completed`
- TO: `### Requirement: Reconciliation seeds already-stored resources as completed`

The requirement is reached on any `joinedEventId` marker mismatch — a fresh provision, an event switch, or
a delete-and-reinstall — so naming it for the "join" describes one occasion out of three. Its normative
content, its seeding behaviour, its authority rules for a successful listing, and all of its scenarios are
unchanged; only the name moves. "Photos" becomes "resources" in the same edit because what is seeded is one
row per stored **resource** (`<assetId>-<role>`), which is what every scenario under it already says.

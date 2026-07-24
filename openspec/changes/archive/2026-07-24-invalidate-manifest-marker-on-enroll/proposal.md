# Enrollment invalidates the device-manifest marker

## Why

Re-joining an event this device has already contributed to leaves the event union holding an **empty**
manifest for this device — every photo it uploaded disappears from everyone else's gallery, permanently,
with no error anywhere.

Two writers reach one remote resource (`PUT /events/<id>/devices/<deviceId>`):

- `ManifestDeviceEnroller` writes a **register-only empty** manifest at join (capability `join-event`,
  *"Confirming enrolls the device, then provisions"*) — deliberately, so that a download-only device is
  still an enumerable member.
- `DeviceManifestProducer` writes the **real** projection each cycle, and skips the PUT when the
  projection equals its `lastUploaded` marker (capability `device-manifest`, *"Sole writer, synchronous
  in-cycle upload"*).

The marker is a belief about *what the server holds*. Enrollment overwrites what the server holds and
never touches the marker, so after any re-enroll the belief is false in the one direction that loses
data: the producer computes the same projection it computed before, matches the stale marker, and
skips — leaving the empty manifest in place until the ledger's projection happens to change. On a
finished event nothing changes it ever again.

Every path that re-enrolls the **same** event id reaches this:

- leave → rejoin (the common one: a user re-scans the QR of an event they left);
- `SNAPSYNC_RESET_STATE` → rejoin (how it was found — the reset clears the config, so the join is no
  longer short-circuited as `AlreadyJoined`);
- a reinstall that restores the App-Group container.

A *switch* to a different event is already safe, because the marker is keyed by event id — that key was
added for exactly this hazard and stops one event's belief from suppressing another's write. It simply
does not cover the same event twice.

This is pre-existing: the accumulator-backed producer had the same marker and the same enrollment, so
the defect predates the ledger projection. It was found on device during
`introduce-candidate-source`'s §6.5 run and is not caused by that change.

## What Changes

- **`DeviceManifestStore` gains `clearLastUploaded()`** — the marker becomes invalidatable, not only
  writable.
- **`ManifestDeviceEnroller` clears the marker after a successful register-only PUT.** The fix sits at
  the write that falsifies the belief, so it covers every enrolling path at once rather than each caller
  remembering.
- `LeaveEvent`'s doc claim that a stale manifest *"self-heals when the producer restarts and re-writes
  the manifest"* becomes true; today the marker is what prevents that rewrite.

Not changed: enrollment still PUTs the empty manifest (the join contract depends on it), and the
producer still skips-if-unchanged (it is what keeps a per-cycle PUT off the wire for an idle device).

## Impact

- Affected specs: `device-manifest`
- Affected code: `domain` `ports/DeviceManifestStore`, `feature/membership/DeviceEnroller`,
  `compose/SnapSyncApp`; the three store impls (`IosDeviceManifestStore`,
  `InMemoryDeviceManifestStore`, and the world's use of it)
- Cost of the fix: one extra manifest PUT per re-join — the very next cycle writes the projection it
  would otherwise have skipped.

# Design

## Context

`DeviceManifestProducer.produce` ends:

```kotlin
val marker = "$eventId $json"
if (marker == store.loadLastUploaded()) return
if (uploader.put(eventId, deviceId, json)) store.saveLastUploaded(marker)
```

`ManifestDeviceEnroller.enroll` calls the **same** `Enrollment.put` with `{deviceId, assets: []}` and
records nothing. So the invariant the marker asserts — *the server's copy equals this projection* — has a
second writer that can break it, and does, on every re-enroll of the same event.

The dangerous shape is not the skip. It is a **cache of a remote fact with one writer that maintains it
and another that invalidates it silently.** The skip is correct and worth keeping: without it an idle
device PUTs an identical manifest every cycle forever.

## Goals / Non-Goals

**Goals**
- Re-joining an event never leaves the union holding an empty manifest for this device.
- The fix holds for *every* enrolling path, present and future, without each one remembering.
- Skip-if-unchanged still suppresses the steady-state PUT.

**Non-Goals**
- Removing the register-only empty PUT. `join-event` requires it, and a download-only member has no
  other way to become enumerable.
- Making the manifest read-back-verified against the server. That would be the general cure for
  "cached remote fact", and it costs a GET per cycle to fix a case that a one-line invalidation fixes
  exactly.
- Reconciling manifests already emptied on the deployed backend. The next cycle after this ships
  rewrites them, because the marker is cleared at the enroll those devices will perform anyway on their
  next re-join. Devices that *never* re-join were never broken.

## Decisions

### D1 — Invalidate at the enroller, not at the reset

`SNAPSYNC_RESET_STATE` is where the bug was found, and it is the wrong place to fix it.

At the moment of a reset the marker is still **true**: the reset changes only local state, and the
server's manifest is untouched. The belief becomes false later, when `enroll` overwrites the server.
Clearing at reset would therefore clear a correct belief and — worse — would leave `leave → rejoin`, the
path a real user actually takes, still broken while looking fixed.

So the clear goes where the falsification happens: immediately after the enroller's successful PUT. Every
enrolling path is covered because there is only one enroller, and a future second one would have to go
through `Enrollment` and would be equally visible.

**Rejected: clear in `JoinEvent`.** It is the caller, so the rule would be "callers of enroll must
remember", which is the class of instruction that decays. `JoinEvent` would also have to hold a
`DeviceManifestStore` it otherwise has no use for.

### D2 — `clearLastUploaded()` on the port, not `saveLastUploaded(null)`

A nullable save reads as "record that the server holds nothing", which is nearly the opposite of what is
meant. `clearLastUploaded()` states the intent — *stop believing anything* — and gives the iOS impl a
file delete rather than a write of the string `"null"`.

### D3 — Clear only on a **successful** PUT

`enroll` returns `false` when the edge did not confirm. A failed PUT did not change the server, so the
marker is still true and clearing it would cost a pointless PUT next cycle. This also mirrors the
producer, which likewise only records on success — the two writers now maintain the marker under the
same rule.

### D4 — No architecture guard for this

The tempting guard is "every `Enrollment.put` call site touches the marker". It would have exactly two
subjects, both in `feature/membership`, both in one file each — a guard whose cost exceeds a reader's
glance. What makes the fix durable is that it is *inside* the enroller rather than beside its callers,
which is D1, not a text gate.

The regression test is a behavioral one instead: enroll, then produce the same projection, and assert
the PUT happened.

## Risks / Trade-offs

- **One extra PUT per re-join.** Intended: it is the write that was being lost.
- **A device that re-joins with an empty ledger still writes an empty manifest.** Correct — it has
  uploaded nothing to that event yet, and the next completed upload rewrites it.

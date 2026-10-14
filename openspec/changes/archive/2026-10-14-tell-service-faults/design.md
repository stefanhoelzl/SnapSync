# Design

## Context

See proposal.md for the why. This change is written as the behaviour members should get, not as an audit of
today's app; where the app differs, the apply phase's tests fail and drive the fixes. These are the current
mechanics that matter:

- **One decoder classifies every answer.** The generated replies (`build-logic`'s wire generator,
  `WireRouteObjects.kt`) sort each answer:
  - `502` becomes `ServerError` and `503` becomes `Maintenance(retryAfter)`, both in the ServerIssue group, and
    every app operation declares both;
  - no answer becomes `Unreachable`;
  - a status the operation does not declare, a JSON body that does not decode, or a missing required header
    becomes `Malformed`, which falls in the NoAnswer group;
  - **a `200` whose declared schema is empty decodes as success whatever the body**, and so does an outcome
    that is a text constant.
- **Four writes read an unparseable answer as success:**
  - joinEvent (`Joined`, `services/backend/Membership.kt:80`);
  - leaveEvent (`Left`, so the pending leave is dropped);
  - publishManifest (`Published`, `Membership.kt:100`, so the snapshot is recorded as published);
  - reportReceived (`Recorded`).
- **deviceFiles reads it as a permanent error.** A `Malformed` listing becomes a `DeviceListingShapeException`
  (`services/backend/Listings.kt:78`) and is logged at error level as a permanent shape fault.
- **Every foreground failure is told with one message per surface.** create gives `CREATE_FAILED` ("Couldn't
  connect. Check your connection…"), rename gives `RENAME_FAILED`, join load gives `LoadFailed`, and join confirm
  gives `CommitFailed` ("Something went wrong"). Nothing tells 502, 503 and no answer apart.
- **Retry-After is read nowhere.** The real service sends 30 s and the mock sends 60 s (`BackendRouter.kt:606`).
  The gateway's only retry is the one after `Unauthorized`.
- **The "deleted" answer is live, not legacy.** The real service answers `410 closed` to joinEvent (the
  enrolment of a completed event), changeEvent (`api/src/routes/shared.ts:211`) and publishManifest
  (`api/src/routes/v2.ts:435`) once the sweep has completed the event. The app handles it on join only. Rename
  folds it into `RENAME_FAILED`, and publish reads it as "published" and stops quietly.
- **The verification line is driven by the attestation outcome.** Anything but `Issued` from challenge, mint or
  renew becomes `TokenOutcome.Unreachable` (`services/trust/DeviceAttestation.kt`), so a 502, a 503 and no
  answer all read alike today.
- **The status line has no service condition.** It has a "cannot be verified" line, and nothing else for a failing
  service.

## Goals / Non-Goals

**Goals:**

- One classification of a failed exchange into the three conditions the member is told (cannot be reached ·
  having trouble · being updated), made once in the backend services and carried to every surface. No surface
  should classify on its own.
- Every new scenario provable by the integration suite over the backend mock's per-operation fault lever.

**Non-Goals:**

- **Faults of the operating system's photo transfers.** The upload and download byte routes are carried by the
  OS (PhotoKit, URLSession, Android's session and DownloadManager), not by the app's HTTP path. The mock's fault
  lever does not reach them (`BackendState.receive` takes no fault), and the app sees only a status-less transfer
  failure. Their behaviour belongs to the transfer-fault phase.
- **The settings save.** It is stored on the device, so a failed save is a storage fault, not a service fault.
- **Changing any timing a member cannot observe.** A background retry stays on the next wake. `Retry-After` gets
  no meaning outside the join screen's load (D3).
- **The api.** No route or status changes. The service already answers everything the specs need.

## Decisions

### D1: Fault → condition mapping

| What the app meets | Condition told | Mock fault |
|---|---|---|
| no answer (the transport failed) | cannot be reached | `UNREACHABLE` |
| an answer the app does not understand (undeclared status, undecodable body, missing required header, a `200` whose body is not the device API) | cannot be reached | `MALFORMED` |
| `502` / any server failure | having trouble | `SERVER_ERROR` |
| `503` maintenance | being updated, back shortly | `MAINTENANCE` |
| `410 closed` | the event is over (the deletion is confirmed) | `ENDED_FOR_GOOD` |
| `409 closed` | none (dead; see D6) | `CLOSED` |

**Why an unreadable answer counts as "cannot be reached".** In practice a `200` whose body is not the device API
almost never comes from SnapSync. It is a captive portal, a proxy or a carrier page answering in the service's
place, so the request never reached the service. The member can fix that only by fixing their connection, which
is the one condition whose words may say so.

**Alternative rejected:** telling it as "having trouble". That blames the service for the member's network and
withholds the one useful hint.

### D2: An unreadable answer to a write is no answer, so nothing counts as done

The four writes in Context stop treating any `200` as success:

- **joinEvent:** the failed-join screen with Retry. If the real service did enrol the device, the Retry's
  re-enrolment is idempotent and lands on the joined screen. The spec already allows that ("if the membership was
  in fact established…").
- **leaveEvent, publishManifest and reportReceived** stay pending and go out again at the next wake. All three
  are idempotent at the service: a repeated leave, a republished manifest version and a repeated report are
  each harmless.
- **createEvent** cannot be safely repeated. A garbled create that did succeed leaves an event nobody holds.
  That is invisible to every member, and the sweep deletes it 30 days after its open-until date. Accepted.

**How:** an operation whose success schema is empty is decoded as success only when the body is empty or is what
the operation declares. Which of those two the generator enforces is the apply phase's choice; either satisfies
the specs. The alternative, a content-type check alone, was rejected because a captive portal can send
`application/json` too, and the mock's `MALFORMED` does.

### D3: Retry-After is honoured only by the join screen's load

The join screen's event load is the one foreground read that rests on a failure message, and the spec already
has it reload by itself when the network returns. During maintenance it reloads once the hint has passed, and
keeps Retry meanwhile.

- **Writes** (create, rename, join confirm) never fire without a tap: the member decides when to commit.
- **Background work** keeps its own cadence. A wake is minutes apart, so a 30-second hint changes nothing a
  member can observe.
- **The hint's value:** the service sends a poll interval (`api/src/app.ts`, `MAINTENANCE_RETRY_AFTER_SECONDS`),
  not an estimate of the window. A reload that meets maintenance again schedules itself again, which converges.
  The apply phase should bound how often the screen reloads, so a malformed or huge hint cannot spin or stall it.

### D4: The condition rides each failure; one cell answers the joined screen's line

A message on a surface must name the failure of *its own* request, and a later exchange must not rewrite it. So the
condition is carried in each outcome rather than read from a shared cell when the screen is drawn:

- **The reading is made once:** `faultOf(reply)` in the data layer, over `ServiceFault.of(BackendAnswer)` in the
  domain. A new `BackendAnswer.Updating` tells maintenance apart from other server failures.
- **Each service puts it in its outcome:** `CreateOutcome.Transient(fault)`, `RenameOutcome.Transient(fault)` (with
  `null` for a `404`, still told generically), `EventLookup.Failed(fault, retryAfterSeconds)`, and
  `JoinResult.Failed(fault)`. The last becomes `JoinOutcome.EnrollFailed(fault)` and then `JoinCommit.Failed(fault)`;
  the two join outcome enums became sealed types to carry it.
- **The attestation records its own:** a refresh the service failed carries the fault through
  `TokenIssuance.NoAnswer(fault)` into a third `AttestationStatus` cell, bracketed per refresh like the other two.
  It answers both the status line ("an expired proof the service failed") and a create or join refused for want of a
  proof, which no longer reads as a generic failure.
- **The status line's cell is `ServiceConditions`:** the outcome of the latest answer of any kind, written at the
  exchange beside the version gate. An opening forgets it (`AppForeground` calls it on each return), so a failure
  from before the opening is never the opening's first word. The line shows only while the health would otherwise be
  "photos waiting", ranked after "cannot be verified".

**Alternative rejected:** per-feature failure flags (upload, download, manifest). They would let three surfaces
disagree about one outage, which is exactly what the member's single line has to avoid.

### D5: A "deleted" answer is the confirmation event-lifetime asks for

The service answers `410` only from a completed event (the sweep wrote `completedAt`) or a legacy closed one.
Both mean the photos are gone and nothing more is accepted.

- **The app records it as the event's deletion,** the same fact the event read's `completedAt` already feeds.
  The joined screen then turns to "over" by the same path, sharing stops, and the member stays.
- **The rename dialog** shows its own "the event is over" message, so it stays open as "A failed rename keeps the
  dialog" requires.
- **A `404` stays disbelieved before the latest day,** as today. Only the explicit `410` confirms.

### D6: `409 closed` gets no scenario, and the mock's `CLOSED` fault is dead

No current route declares a `409 closed` answer.

- **Where it falls instead:** on publishManifest the generated decoder reads a `409` as `SetFixed` (JSON body)
  or `NotAMember` (text), and on mint and renew as `StaleChallenge`, whatever the body.
- **Why no test:** a test using the mock's `CLOSED` would prove a decoding accident, not a member-visible
  behaviour.
- **One test still uses it, on purpose:** "The phone cannot give its proof" needs the service to answer a renewal
  with no proof, and a `409` of any body is what the renewal route reads as a stale challenge. Deleting the fault
  means giving that test a stale-challenge lever first.
- **Cleanup:** deleting `BackendFault.CLOSED` (and correcting `ENDED_FOR_GOOD`'s KDoc, which calls a live answer
  legacy) is a separate cleanup, outside this change. It changes the mock, not behaviour.

### D7: How the tasks are sliced

There is one task group per surface, each with its tests first and the fixes they force after.

- **How a test is written:** it claims its scenario with `@Verifies`, one behaviour per scenario, in the
  integration suite's behaviour DSL. A negative outcome is asserted with `Settled`, and the maintenance reload's
  wait runs on the mocked clock's timers, never in real time.
- **How a fault is driven:** through the control channel's per-operation lever, which an earlier phase of the
  programme adds.
- **Coverage:** the change ends by raising the shared integration-coverage default to what it measures.

### D8: Two small additions to the test infrastructure

- **`BackendLevers.recover(operation?)`** lifts what `fail` set. Without it, an outage can only run out, never end,
  and "once the service works again" could not be played.
- **The `/user` verb `createAgain`** taps Create over the form as it stands. A failed create's retry must not retype
  the form, or "a retry is one tap" and "the sheet asks again" could not be shown.

Both extend phase ①'s typed channel rather than going around it. The lever's real-backend host refuses it, like every
other operator lever.

### D9: An undecodable device listing is no answer, not a permanent fault

The per-device listing told an answer it could not decode apart as a `DeviceListingShapeException`, logged at error
level as permanent. Under D2 an undecodable answer is *cannot be reached*, so it is now a transient failure, retried.
What a member sees is unchanged: both paths already kept only the rows the membership had.

**Trade-off:** a listing in a shape this build does not know now logs as a warning. The strict decoding still keeps it
from seeding anything, and a listing that decodes but names a non-canonical asset is still the shape error.

## Risks / Trade-offs

- **[Risk] The apply's prerequisites are not on `main`.** These are the control channel's per-operation fault
  lever (today it exposes only `backend/fail-listing` and `backend/fail-union`), the integration-coverage check,
  and the behaviour DSL.
  → **Mitigation:** task 0.1 checks for all three. If one is missing, the apply waits for its phase rather than
  adding a private lever or helper.
- **[Risk] The domain-layer refactor on `main` moves the files Context cites.**
  → **Mitigation:** the file and line references are as of the proposal. Task 0.2 re-reads them after the rebase.
- **[Risk] Stricter success decoding could turn a harmless proxy quirk into a retry loop.** An example is a
  CDN appending a body to an empty `200`.
  → **Mitigation:** the four operations are idempotent, and the retry rides the existing wake cadence. The worst
  case is repeated, harmless requests, never a lost leave or share.
- **[Risk] The new status line flickers during short outages.**
  → **Mitigation:** it shows only after the opening's own attempt failed, and clears on the first success, as
  the verification line does. A single blip between two successful passes never reaches it.
- **[Trade-off] Rewriting two decided verification scenarios** ("Unreachable server with an expired
  verification", "A failure without a verdict names no cause"). This deliberately reverses part of the earlier
  decision. "Cannot be verified" read as the phone's fault when the cause was SnapSync's outage, which broke the
  requirement's own "never blamed on the member".
- **[Trade-off] A garbled create may leave an orphan event,** swept after 30 days (D2).

# Proposal

## Why

The specs say little about what a member sees when SnapSync's service fails. Most paths have no scenario, so no
integration test can claim one. Where the app does handle a failure, it tells every kind the same way, and that
way often blames the member's own connection. A garbled answer, such as a Wi-Fi login page, even counts as
success for a join, a leave, the shared set and the "received everything" report. This change writes down the
behaviour members should get, so that tests can verify it and drive the fixes.

## What Changes

- **A service failure is told as one of three conditions**, each in its own words, wherever the member sees a
  failure:
  - SnapSync *cannot be reached*: no answer, or an answer that did not come from SnapSync;
  - SnapSync *is having trouble*;
  - SnapSync *is being updated and is back shortly*.

  Only *cannot be reached* may suggest checking the connection.
- **An answer the app cannot understand is no answer.** It is told as *cannot be reached*, nothing counts as
  done, and the work is tried again.
- **Create, rename and join tell which of the three applies.** They still wait for the member's tap to try again.
- **The join screen's event load, when the service is being updated**, says so, keeps Retry, and loads again by
  itself once the service suggests it is worth asking again.
- **A new status line on the joined screen.** While photos wait to be shared or received and the service is
  failing, the joined screen says which of the three applies and that the photos are safe. It ranks just below
  "cannot be verified".
- **A failed renewal of the phone's proof that is the service's fault shows the service's line**, not "this
  device cannot be verified" (**BREAKING** for two existing scenarios, which are rewritten). "Cannot be
  verified" stays for when the service answered but no proof could be obtained.
- **Background promises under a failing service:**
  - a leave the service failed to take still counts;
  - photos shared while the service fails reach the others once it works;
  - a member whose "received everything" could not be told never makes the event's deletion earlier;
  - a failed wake registration only delays received photos.
- **The service saying the event's photos were deleted confirms it.** This holds when the answer comes to a rename
  or to the member's sharing. The rename dialog says the event is over, the joined screen shows it as over, and
  the member stays in the event.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-experience`: a new requirement for how a service failure is told (three conditions; an answer not
  understood is no answer).
- `create-event`: a failed create tells the service's condition.
- `join-event`: the event load tells the condition and, during an update, loads again by itself; a failed join
  tells the condition, and an answer not understood is not a join.
- `manage-membership`: a failed rename tells the condition; a rename of a deleted event says the event is over.
- `sync-status`: the new service line and its place in the priority; a renewal the service fails shows the
  service line instead of "cannot be verified".
- `event-lifetime`: a leave and a "received everything" the service failed to take; a "deleted" answer to the
  member's sharing confirms the deletion.
- `delivery`: photos shared while the service fails reach the others later; a failed wake registration only
  delays received photos.

## Impact

- **One PR.** This change is proposed, applied and archived in this workspace, and ships as one PR: tests first,
  then the fixes they force. The apply waits on three earlier phases: the control channel's per-operation fault
  lever, the integration-coverage check, and the behaviour DSL the tests are written in.
- **Coverage:** the shared integration-coverage default is raised to what this change measures.
- **Code the apply phase will touch:**
  - the backend services' reading of answers (four writes that read an unparseable answer as success, and a
    listing read that logs one as a permanent error);
  - the failure messages of the create, rename and join surfaces;
  - the status line's priority;
  - the verification line;
  - the join screen's reload after an update;
  - the handling of a "deleted" answer to rename and to the shared set.
- **Test infrastructure:** the integration suite needs the backend mock's per-operation fault lever on the
  control channel (an earlier phase of the programme).
- **Out of scope:**
  - faults of the photo transfers themselves (the operating system's uploads and downloads);
  - the settings save, which is local;
  - faults of any other system.

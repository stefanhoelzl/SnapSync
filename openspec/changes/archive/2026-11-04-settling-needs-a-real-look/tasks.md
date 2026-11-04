# Tasks

Ships in the phase-4.4c pull request (label `bug`). Group 1 runs against today's code before the move. Groups 2–3 are
built into the move, whose structural tasks live in the phase's review document.

## 1. The failing behaviour, against today's code

All in `test/integration/.../SettlingIntegrationTest.kt` (`@Verifies` event-lifetime, "A member's share is settled only
after their own range has ended"). The app-only scenarios use the new shared step `appUploadsOnly`.

- [x] 1.1 "A walk that finds the library unreadable after the end settles nothing":
  - set up: full grant, the app uploading, the range ended, a photo in range;
  - act: a wake's walk is parked reading the library, access is withdrawn, then the walk is released.
  - Verify: FAILS on today's code — the manifest goes out `final` and declares no photo at all.
  - Also: "a library that cannot be read" (the platform's read throws) pins that a failed pass publishes nothing, and
    that the next look settles with the photo. Green on today's code.
- [x] 1.2 "A walk begun before the end settles nothing however late it publishes":
  - act: a photo taken just before the end, a wake's walk parked reading, the clock moved past the end, then the walk
    released.
  - Verify: FAILS on today's code (`final`).
- [x] 1.3 "Rejoining after the end settles again":
  - set up: the range ended and the member settled;
  - act: they leave, the backend's device listing fails, they rejoin, a cycle runs.
  - Verify: FAILS on today's code — nothing is published after the rejoin.
- [x] 1.4 "A member who shares nothing settles at the end": receive-only, range ended. The member ends up `settled`
  with an empty manifest. Verify: green on today's code (pins D2).
- [x] 1.5 Settling under limited access:
  - the selection held when the range ended settles at the end of a wake;
  - with no selection read yet, the end of a wake settles nothing, and the selection's first read settles the share.
  - Verify: both green on today's code (the known coverage gap).
  - Measured: the app's walk memo answers an unchanged library "as of now", so a memoised read after the end is a look
    since the end (D5 holds for full access too).

## 2. Settled as evidence (D1–D5, built in the 4.4c move)

- [x] 2.1 Add `SharePhase` to the domain (D4) and `ShareDeclaration.of` with `SettledShare`'s opt-in marker (D1, D2).
  - Verify: unit tests for every branch: not ended; ended + a discovery after the end; ended + a discovery at or
    before the end; ended + no discovery; a declining policy before and after the end.
  - Verify: `OptInPinsTest` pins the one mint.
- [x] 2.2 The publish takes its declaration only from `ShareDeclaration.of`, and skips a non-proving publish after a
  published `Settled` for an ended range (D3).
  - Verify: unit tests of the publish use case (sent `Settled`, sent `Sharing`, skipped after `Settled`, `Sharing`
    sent after the range moved later).
  - Verify: tasks 1.1, 1.2, 1.4 and 1.5 pass.
- [x] 2.3 Correct the docs and KDoc drift found in the audit:
  - `docs/architecture.md`'s manifest route row ("honoured only after `endsAt`") and §807/§830's event-end wording,
    now in terms of the member's own range;
  - the manifest's "closes the event" and "write-only" KDoc;
  - `api/src/app.ts`'s "closes the event" comment.
  - Verify: grep finds none of these phrases left.

## 3. A join advances the manifest version (D6)

- [x] 3.1 `MembershipWrites.enter` advances the manifest version in the join's transaction.
  - Verify: a `:test:data` unit test (the version after an enter is higher, on a first join and on a rejoin of the
    same event).
  - Verify: task 1.3 passes.

## 4. The sync-status wording

- [x] 4.1 Integration or UI-state test for "Everyone has shared": every member has finished sharing while one has not
  received every photo, and the screen says everyone has shared (`@Verifies` sync-status, "An ended event is marked
  without stopping sync"). Verify: green on today's code (the delta only aligns the spec with it).

## 5. Integration

- [x] 5.1 Run `./gradlew build` and `./gradlew :test:architecture:verifiesReport`.
  - Verify: green, and every scenario this change adds or touches is listed.
  - Verify: the archive gates in `openspec/config.yaml` (placeholder Purpose, no code identifiers) print nothing for
    `event-lifetime` and `sync-status`.

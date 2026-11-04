# Tasks

Prerequisite: phase 4.4b's move of the ledger and both transfer tiers into the data layer (its commit "move I") has
landed on the branch — `UploadRequests`, `OsQueueTier` and `SessionTier` exist in `:domain:data`.

## 1. The failing behaviour, against today's code

- [x] 1.1 Add to `TrustIntegrationTest` the scenario "A phone whose proof was refused sends no photo until it is verified
  again" (`@Verifies` privacy-security, that scenario): extension uploads only, joined, the backend refuses the
  extension's proof on its publish so the extension drops it, a new photo, a `cycle()` — then `never<UploadJobCreated>`
  for it and no `BytesStored`; after `os.foreground()` and the app's verification, `BytesStored` for it. Verify: it FAILS
  on the branch before task 2 (a token-less job is created), and `./gradlew :test:architecture:verifiesReport` lists the
  new scenario as claimed.

## 2. The gated request builder

- [x] 2.1 `UploadRequests.first` / `retry` take the token the gated way (held → built; none → `Credential.missing()`;
  still none or unreadable → `RequestBuilt.NoToken`). Verify: unit tests in `:domain:data` for held, none-then-minted,
  none-then-none and unreadable; no `Authorization`-less request is constructible (the `Built` case requires a
  `BearerToken`).
- [x] 2.2 `OsQueueTier.create` and `SessionTier.create` answer `Withheld` on `NoToken` and record nothing; the creation pass
  stops at it and answers "withheld" (D2). Verify: tier unit tests (row stays `DISCOVERED`, no job created, pass stopped)
  and the creation use case's test for the stop.
- [x] 2.3 `OsQueueTier.retry` skips the re-point on `NoToken`, leaving the offered job and its `REQUESTED` row (D3). Verify:
  unit test over the `OsUploadQueue` mock — the offered retry is still offered, nothing re-pointed.
- [x] 2.4 The extension answers `COMPLETED` for a withheld pass, the app keeps "work left" (D2). Verify: unit tests of
  `CycleAnswer.of` / `UploadsLeftOver.of` for the withheld case; task 1.1 now passes.
- [x] 2.5 KDoc: the request builder states the gated rule and why (replacing "a null token still yields a request");
  `StatusContainerHost`'s "uploads are gated on an attestation token" comment is now true — leave it, cite this change.
  Verify: `./gradlew build` green, including `TrustIntegrationTest`'s expired-proof scenario unchanged (D4).

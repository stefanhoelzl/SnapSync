## MODIFIED Requirements

### Requirement: Run the in-app port contracts on a simulator on every push

**The job.** The system SHALL run a job `ios-contracts` in `.github/workflows/ios.yml` on every push, on a
`macos-26` hosted runner, in parallel with `ios-build` and `ios-test`.

**Building and installing.** The job SHALL:
1. build the app with `-Psnapsync.rig=true` for the iOS simulator, against the `local` deployment;
2. ad-hoc sign it with `scripts/sim-sign`;
3. install it on **one freshly created** simulator. One, because a second freshly created simulator's first-boot
   work swamps the hosted runner: measured, it tripled the job and made the journeys flaky;
4. grant photo access to the app's bundle, with a version-pinned `applesimutils`, before the first launch;
5. start the **loopback transfer fixture server** the transport contracts exchange bytes with (capability
   `port-contracts`, "An adapter bound per compilation target is real for the clauses it runs there") on a
   port chosen for this run;
6. start the **real backend** (`api/`) locally on the loopback address and port the `local` deployment names,
   with a filesystem store fresh for the run, and warm it with one request before the app launches;
7. launch the app on a rig port chosen for this run.

**Photo-library readiness.** A fresh simulator's photo library is not ready when the simulator reports it booted:
its first write can wait minutes. The job SHALL start a photo-library warm-up right after boot, and SHALL wait for
the library to be ready as an explicit, timestamped stage before the first contract, so that wait is attributed to
the platform rather than to whichever contract touches the library first.

**Running.** It SHALL then:
- read the app's `GET /device` advertisement once, and fail if it names an unclassified entry or an entry
  outside the vocabulary;
- run every contract in the simulator app's registry through the rig's contract verb, passing the
  fixture's base URL (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig");
- run the all-real journeys (capability `testing-architecture`, "All-real journeys are the contracts' safety
  net") against the app and the local backend.

**The status check.** It SHALL post the `ios-contracts` status-check context. The check SHALL conclude as
failure when:
- any clause reads `Failed` or `NotWithin` — a bounded wait on an operating-system callback that expired ran
  nothing, and on a host CI runs live that is not coverage;
- a run is refused;
- the registry is empty;
- a journey fails;
- an advertisement is incomplete;
- the fixture server or the backend does not answer before launch;
- an app never answers on its rig port. In that case the job SHALL capture a simulator screenshot and the
  app's log, because a pending system alert is visible only there.

**Evidence.** The job SHALL keep, with its evidence:
- the fixture server's request log;
- the backend's output, including a log of every request it served (method, path, status, duration);
- the host's memory and CPU load, sampled throughout the run;
- the host's and the simulator's crash reports;
- on a journey failure, the failing assertion's message, printed in the job log and not only kept in the test
  report.

#### Scenario: A contract clause fails in the simulator app
- **WHEN** a clause run in the simulator app reads `Failed`
- **THEN** the `ios-contracts` check concludes as failure and its log carries the full outcome table

#### Scenario: The grant did not take
- **WHEN** photo access was not granted before launch and the contract verb refuses the run
- **THEN** the `ios-contracts` check concludes as failure rather than reporting the unrun clauses as passed

#### Scenario: The app never answers
- **WHEN** the launched app does not answer on the rig port
- **THEN** the job fails with a screenshot and the app log attached, rather than timing out without evidence

#### Scenario: The fixture server is not up
- **WHEN** the transfer fixture server does not answer its health route before the app is launched
- **THEN** the job fails naming the fixture, rather than letting every transport clause fail as a transfer
  error

#### Scenario: A transfer's callback never comes
- **WHEN** a transport clause's bounded wait expires and the clause reads `NotWithin`
- **THEN** the `ios-contracts` check concludes as failure, rather than passing a clause that observed nothing

#### Scenario: A journey fails
- **WHEN** a journey's awaited outcome is not reached within its bound
- **THEN** the `ios-contracts` check concludes as failure, printing the failing assertion's message, with the
  app's log, the backend's request log and the resource samples kept

#### Scenario: The backend is not up
- **WHEN** the local backend does not answer before the app launches
- **THEN** the job fails naming the backend, rather than letting every journey time out

#### Scenario: An app host's vocabulary has a gap
- **WHEN** the app's `GET /device` names an unclassified entry
- **THEN** the `ios-contracts` check concludes as failure naming the entry

#### Scenario: The photo library's first-use wait is its own stage
- **WHEN** a fresh simulator's photo library takes minutes to become ready
- **THEN** the job's stage timestamps attribute that wait to the readiness stage, and the first contract's
  timing no longer carries it

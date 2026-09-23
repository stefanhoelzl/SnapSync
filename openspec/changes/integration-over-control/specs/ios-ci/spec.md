## MODIFIED Requirements

### Requirement: Run the in-app port contracts on a simulator on every push

**The job.** The system SHALL run a job `ios-contracts` in `.github/workflows/ios.yml` on every push, on a
`macos-26` hosted runner, in parallel with `ios-build` and `ios-test`.

**Building and installing.** The job SHALL:
1. build the app with `-Psnapsync.rig=true` for the iOS simulator, against the `local` deployment;
2. ad-hoc sign it with `scripts/sim-sign`;
3. install it on **two freshly created** simulators;
4. grant photo access to the app's bundle on each, with a version-pinned `applesimutils`, before the first
   launch;
5. start the **loopback transfer fixture server** the transport contracts exchange bytes with (capability
   `port-contracts`, "An adapter bound per compilation target is real for the clauses it runs there") on a
   port chosen for this run;
6. start the **real backend** (`api/`) locally on the loopback address and port the `local` deployment names,
   with a filesystem store fresh for the run, and warm it with one request before either app launches;
7. launch both apps, each on its own rig port.

**Running.** It SHALL then:
- read each app's `GET /device` advertisement once, and fail if either names an unclassified entry or an entry
  outside the vocabulary;
- run every contract in the first simulator app's registry through the rig's contract verb, passing the
  fixture's base URL (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig");
- run the all-real journeys (capability `testing-architecture`, "All-real journeys are the contracts' safety
  net") against both apps and the local backend.

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

**Evidence.** The fixture server's request log and the backend's output SHALL be kept with the job's evidence.

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
- **THEN** the `ios-contracts` check concludes as failure, with both apps' logs and the backend's output kept

#### Scenario: The backend is not up
- **WHEN** the local backend does not answer before the apps launch
- **THEN** the job fails naming the backend, rather than letting every journey time out

#### Scenario: An app host's vocabulary has a gap
- **WHEN** an app's `GET /device` names an unclassified entry
- **THEN** the `ios-contracts` check concludes as failure naming the entry

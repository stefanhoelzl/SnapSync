## MODIFIED Requirements

### Requirement: Run the in-app port contracts on a simulator on every push

The system SHALL run a job `ios-contracts` in `.github/workflows/ios.yml` on every push, on a `macos-26` hosted runner, in parallel with `ios-build` and `ios-test`. It SHALL build the app with `-Psnapsync.rig=true` for the iOS simulator, ad-hoc sign it with `scripts/sim-sign`, install it on a **freshly created** simulator, grant photo access to the app's bundle with a version-pinned `applesimutils` before the first launch, start the **loopback transfer fixture server** the transport contracts exchange bytes with (capability `port-contracts`, "An adapter bound per compilation target is real for the clauses it runs there") on a port chosen for this run, launch the app, and run every contract in the simulator-app registry through the rig's contract verb, passing the fixture's base URL (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig"). It SHALL post the `ios-contracts` status-check context, concluding as failure when any clause reads `Failed`, when a run is refused, when the registry is empty, when the fixture server does not answer before launch, or when the app never answers on the rig port — capturing a simulator screenshot and the app's log in that last case, because a pending system alert is visible only there. The fixture server's request log SHALL be kept with the job's evidence.

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

## ADDED Requirements

### Requirement: The world's transfer doubles wrap honest fakes

The world's `BackgroundTransfer` and `DownloadTransport` doubles SHALL be **wrappers** over the honest fakes
`:adapter:generic:fake` provides for those ports, which the port contracts bind (capability `port-contracts`).
The wrappers SHALL keep the operator levers and inspection this spec requires of them — the settable job-limit,
the forced create-failure, the operator complete and fail actions, the operator stage action and its chosen
`TransferOutcome`, the inspectable buckets and started transfers — and SHALL add no behaviour of their own
between a lever and the honest fake. A lever SHALL act by withholding, releasing or choosing what the honest
fake then does, so a world test and a contract clause observe the same answer to the same input.

#### Scenario: A world upload completes through the honest fake

- **WHEN** the operator completes a created job
- **THEN** the object lands and the ledger records `COMPLETED` through the same honest fake the
  `BackgroundTransfer` contract's `Fake` binding runs

#### Scenario: A lever the honest fake cannot express

- **WHEN** a lever needs an answer the honest fake would never give for the same input
- **THEN** the lever is removed or the honest fake is corrected against its contract, rather than the wrapper
  answering on its own

## ADDED Requirements

### Requirement: The transport-ledger gate

`:test:architecture` SHALL assert, by source text, that **no transport adapter references `LedgerStore`**
(`sync-ledger`, "Reader and writer capability split"). A transport adapter is any production source file under
`adapter/` that declares an implementation of `BackgroundTransfer` or the target-bound `uploadJobQueue`
factory; the scope SHALL be derived from that text rather than from a list of paths, so a new transport is
covered the moment it declares the supertype. The gate SHALL read **code**, with comments and KDoc stripped,
so a transport's documentation may still explain why it holds no ledger store.

A source-text gate is the mechanism because neither the compiler nor the module graph can withhold the type.
`LedgerStore` is declared in `:domain` `ports/`, the same module every transport must depend on to implement
`BackgroundTransfer`, and `:adapter:ios:ext-safe` additionally depends on `:domain` `feature/`. Narrowing what a
transport is *handed* to a `TransferRecord` makes the boundary visible in each constructor; only this gate
stops the next edit from widening it back.

The gate SHALL fail on an empty scope, so a regression in how transports are recognised cannot make it pass by
inspecting nothing.

#### Scenario: A transport takes a LedgerStore again
- **WHEN** a production file under `adapter/` that implements `BackgroundTransfer` names `LedgerStore` in code
- **THEN** the gate fails and names the file

#### Scenario: Documentation may name the store
- **WHEN** a transport's KDoc or comment mentions `LedgerStore`, and its code does not
- **THEN** the gate passes

#### Scenario: A new transport is covered without editing the gate
- **WHEN** a new adapter file declares an implementation of `BackgroundTransfer`
- **THEN** it is inside the gate's scope with no change to the gate

#### Scenario: A gate that finds no transport fails
- **WHEN** the gate's scope resolves to no file
- **THEN** it fails rather than reporting success

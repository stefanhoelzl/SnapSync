## MODIFIED Requirements

### Requirement: The test-only modules and what each provides

The `:test:*` modules SHALL exist only where they provide something a production module may not, and
SHALL remain exempt from the production-module laws (`module-architecture`, "The module set
withholds"):

- **`:test:world`** — the controllable in-memory world, consumed by both `:app:desktop` and
  `:test:integration` (capability `harness-world-model`).
- **`:test:contracts`** — the port-contract mechanism and every port contract, consumed by the
  bindings' test source sets and linked into the app only under `-Psnapsync.rig=true` (capability
  `port-contracts`).
- **`:test:integration`** — the seam-to-UI-state surface above.
- **`:test:architecture`** — JVM guards over the repository's own text (capability
  `architecture-guards`, which owns what each guard checks).
- **`:test:rig`** — the control channel: one HTTP protocol over a whole running application, served by
  two hosts ("One control protocol, served by two hosts"). Its iOS host is contained at compile time; its
  JVM host composes the world, and is tested in the canonical check through `:test:control`.
- **`:test:control`** — the typed JVM client of that protocol, and the home of the JVM host's tests.
- **`:test:edge`** — the real backend served as a local process for JVM tests: the backend contracts'
  live bindings and the world's real-backend option both stand on it.
- **`:test:harness-driver`** — non-gating dev infrastructure with no spec.

No production module's **main** source set SHALL depend on a `:test:*` module. A source set that a
build script adds only under a containment property (`module-architecture`, "A build-time-only module
is contained by compilation, not by a runtime check") is not a main source set of a production build,
and MAY depend on the contained module that property links.

#### Scenario: A production module reaches for test infrastructure

- **WHEN** a production module's main source set declares a dependency on a `:test:*` module
- **THEN** the dependency is rejected; a test source set extending a shared contract is a test
  compilation and introduces no production edge

## ADDED Requirements

### Requirement: One control protocol, served by two hosts

The control channel (`:test:rig`) SHALL be one HTTP protocol, served by two hosts from the same server,
routes and state projection:
- the **app host**: the rig build of the iOS app, on a device or a simulator, over real ports;
- the **JVM host**: a JVM process whose application is the world's composed core (capability
  `harness-world-model`), over the world's doubles and the backend the world was built with.

A host SHALL differ only in the hook it hands the server, never in a route or in the state encoding.
Both hosts SHALL bind the loopback address only.

The protocol's verbs SHALL be those the channel already speaks — operating-system entry points (`/os`), user
commands at the intent level (`/user`), and device state and verbs (`/device`) — plus `/health`. It SHALL
carry no click, semantics-tree or pixel verb: taps and pixels belong to the UI tier.

The `/device` and `/os` verbs SHALL form **one closed vocabulary**, and every host SHALL classify every entry
as honoured or refused, with a reason:
- `GET /device` SHALL answer that host's classification.
- A request for a refused entry SHALL answer `409` with the reason, never `404` and never a success that did
  nothing.
- A verb outside the vocabulary SHALL answer `404`.
- An entry a host leaves unclassified SHALL make `GET /device` fail naming it, without stopping the host.

A verb both hosts can honour SHALL have one request and response shape on both.

The JVM host SHALL expose the full-stack world inspector's levers (capability `full-stack-harness`) as
`/device` verbs, which the app host refuses. It SHALL refuse the contract verb, naming its host, because JVM
contract bindings run in the canonical check.

The JVM host SHALL be tested in the canonical check through the typed client, over both of the world's
backends. Those tests prove the protocol's fidelity to the application: routes, the state encoding, the
advertisement, the refusals and the client. They do not replace the in-process integration surface
("The seam-to-UI-state integration surface"), which remains the behavioural suite.

The iOS host's gallery seeder and wiper remain the channel's one untested code, for the reason its build
file records.

#### Scenario: The same state from either host

- **WHEN** a client reads `/device/state` from the JVM host and from the app host
- **THEN** both bodies decode to the same state type through the same compiler-generated encoder, carrying
  the real reduced UI state

#### Scenario: A host cannot honour a shared verb

- **WHEN** a client calls, on the app host, a world lever such as backend-offline
- **THEN** the host answers `409` with the reason, and `GET /device` on that host lists the lever as refused
  with the same reason

#### Scenario: The JVM host is asked to run a contract

- **WHEN** a client calls the contract verb on the JVM host
- **THEN** it answers `409` naming the `JVM` host and that JVM contracts run in the canonical check

#### Scenario: A vocabulary entry is added but not classified

- **WHEN** a verb joins the vocabulary and a host's hook does not classify it
- **THEN** that host's `GET /device` fails naming the verb, and the JVM host's tests fail in the canonical
  check

#### Scenario: A test asks for pixels

- **WHEN** a test needs a tap or a rendered pixel
- **THEN** it belongs in the UI tier's tests, because the protocol carries no such verb

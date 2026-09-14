## 0. Branch

- [x] 0.1 Implement on `metrickit-exit-attribution`, beside `add-os-exit-attribution`, and ship both in one
      PR. **Revised from "branch from `main` after that merges"**: only task 3.1 needs the MetricKit
      callbacks, but they exist only on that branch, and waiting bought nothing — both changes carry
      the `internal` label, each keeps its own OpenSpec change and decision record, and the bleed was
      measured on that branch's callback. The cost accepted is a larger PR.

## 1. Tests first (`:adapter:ios:ext-safe` `iosTest`)

- [x] 1.1 A thread-scoped claim prefixes a line logged on its own thread.
- [x] 1.2 A line logged from another thread while a thread-scoped claim is held carries no prefix. Get
      the second thread from a private `NSOperationQueue` (not GCD: the extension-safety gate scans
      this test source set and does not permit `platform.darwin`).
      **Done in `LogContextTest`**, which also checks the helper really ran on another thread (a same-thread
      helper would make every cross-thread assertion vacuous), plus a writer-level case replaying the
      measured shape through `FileLogWriter`. Native test compile verified on Linux; running is 4.2.
- [x] 1.3 A process-wide claim still prefixes a line logged from another thread (the 2026-07-06 hop
      guarantee, pinned so this change cannot erode it).
- [x] 1.4 A process-wide enter nested under a thread-scoped claim claims nothing: the nested line keeps
      the outer prefix, and a line from another thread stays unprefixed.
- [x] 1.5 A thread-scoped enter succeeds on its thread while a process-wide claim is held, and its
      thread's lines carry the thread-scoped name.
- [x] 1.6 Exiting an owned thread-scoped claim restores the process-wide prefix on that thread.

## 2. The mechanism (`:adapter:ios:ext-safe`)

- [x] 2.1 Add the thread-scoped slot to `LogContext` as a `@kotlin.native.concurrent.ThreadLocal`
      object, with `enterThread`/`exitThread` honouring "only the establishing call clears it".
- [x] 2.2 `LogContext.current` resolves the thread-scoped claim before the process-wide one; the three
      writers stay untouched.
- [x] 2.3 `LogContext.enter` (process-wide) refuses when the calling thread holds a thread-scoped claim.
- [x] 2.4 Add `IosThreadLogScope : LogScope` beside `IosLogScope`, with KDoc stating D4's rule for who
      may use it. Update `LogContext`'s KDoc: the serial-delivery justification goes, the measured
      overlap comes in.

## 3. The call sites (`:adapter:ios:app-only`)

- [x] 3.1 Both MetricKit callbacks call `log.invocation(IosThreadLogScope, …)`. KDoc names why they
      qualify (inline handling, nothing launched) and what a future async edit would cost.

## 4. Build

- [x] 4.1 `./gradlew build` green, including `compileIosMainKotlinMetadata`. **Green** (after the extension-safety gate refused GCD in the test; see 1.2).
- [ ] 4.2 `iosSimulatorArm64Test` for `:adapter:ios:ext-safe` green — on the ssh Mac or on CI; Linux
      cannot run it. Record which.
- [x] 4.3 `./gradlew architectureDiagrams`; commit anything it changes. **`architecture/ports.md` gained `IosThreadLogScope`.**

## 5. Specs

- [ ] 5.1 At sync: also update `diagnostic-logging`'s Purpose, which names `LogContext`/`IosLogScope` as
      "the process-global ambient context", and add this change as a decision record citation.
- [ ] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 6. On device

- [ ] 6.1 After a TestFlight build carrying this lands, read the first MetricKit delivery that overlaps
      launch work: the delivery's lines carry `[didReceiveMetricPayloads]`, and the concurrent launch
      lines do not. Deliveries arrive about daily, so this waits on one; record device, OS and date.

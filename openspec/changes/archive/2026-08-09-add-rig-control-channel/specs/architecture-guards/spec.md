## ADDED Requirements

### Requirement: Source contributed into a shell's source set is shell source for the gates
The shell gates SHALL scan every source directory that is compiled into a `:app:*` module, including
directories contributed from another module by the build script. The scanned-root list of the detekt gate
and the mirrored list in its non-vacuity guard SHALL name such directories explicitly, and SHALL move
together.

The gates select their input **by path**, not by Gradle source-set membership, so a directory that
compiles into a shell but lives outside the shell's own tree is invisible to them by default — and the
shell's decision-free guarantee would then be true only of the part of the shell someone remembered to
list. A documented exemption is not an acceptable substitute: a rule that a reader must remember is the
failure mode these gates exist to remove.

#### Scenario: A contributed directory is scanned like any other shell source
- **WHEN** a build script adds a source directory from another module to a `:app:*` module's source set
- **THEN** that directory appears in the shell gate's scanned roots, and a conditional placed in it fails
  the canonical build exactly as one in the shell's own tree would

#### Scenario: A contributed directory is not exempted by comment
- **WHEN** a contributed directory is left out of the scanned roots and its exclusion is recorded only as
  a comment
- **THEN** that is a defect: the exclusion SHALL be removed by listing the directory, or the directory
  SHALL not be contributed into a shell at all

### Requirement: The control channel's trigger coverage is derived, never hand-enumerated
Where a dev/test control surface exposes platform entry points, the set it exposes SHALL be **derived**
from the same entry-point population the entry-point guard derives, and every member SHALL be either
wired to a trigger or named in an exclusion list carrying its reason. A guard SHALL assert that the
derived population equals the wired set plus the excluded set, exactly, in both directions.

A hand-picked trigger list rots invisibly: a new OS callback simply cannot be driven, and the only symptom
is a test nobody wrote. Deriving it means adding an entry point fails the build until its disposition is
stated, which is the same bargain the entry-point guard already imposes.

An exclusion SHALL name the consequence that makes it safe. Re-invoking an entry point that registers
process-lifetime observers, or one that reads a process environment fixed for the life of the process, is
a defect rather than an omission, and the reason distinguishes the two.

#### Scenario: A new entry point is added without a trigger disposition
- **WHEN** a new platform entry point is added to a composition root
- **THEN** the coverage guard fails until the entry point is either wired to a trigger or excluded with a
  stated reason

#### Scenario: An exclusion is recorded without a reason
- **WHEN** an entry point is listed as excluded with no reason
- **THEN** the guard fails, because an unreasoned exclusion is indistinguishable from an oversight

#### Scenario: A wired trigger is removed
- **WHEN** a trigger is deleted but its entry point still exists
- **THEN** the guard fails until the entry point moves to the exclusion list with its reason

### Requirement: A dev/test control channel binds the loopback address only
A control channel served from inside the app SHALL bind the loopback address and no other. A guard SHALL
assert that the channel's source names no bind address but the loopback constant.

The channel forces OS callbacks and exposes event state, and the device it runs on is a phone attached to
whatever network it happens to be on. Widening the bind address is a one-token edit that reads as fixing a
connectivity problem, and nothing about the change would look like a security decision to the person
making it.

#### Scenario: A widened bind address fails the build
- **WHEN** the channel's source names any bind address other than the loopback constant
- **THEN** the guard fails, naming that the channel is reachable only through a host-side port forward

#### Scenario: The channel cannot bind
- **WHEN** the channel's bind fails — for example because a previous instance of the app is still alive
  and holding the port
- **THEN** the app SHALL continue to run unaffected, and the failure SHALL be logged at `Error` severity
  naming the address, the port, and that the channel is not listening — because a refused connection is
  otherwise indistinguishable from an app that is not running or a port forward that was never set up

### Requirement: The OS-receipt expiry line is pinned
The diagnostic line emitted when an OS-handler receipt is released on its deadline SHALL be pinned by a
guard, in the same manner as other cross-boundary literals.

The line is emitted on the expiry path and on no other, which makes its presence the only authoritative
answer to whether the app released a handler because its work finished or because the bound fired. Any
consumer reading it therefore treats **absence** as "the work finished" — so rewording the line turns every
consumer green while hiding exactly the class of defect it was watching for. The failure is silent and in
the dangerous direction.

#### Scenario: The expiry line is reworded
- **WHEN** the text of the receipt's deadline-expiry log line changes
- **THEN** the pin guard fails, naming the consumers that read it as ground truth

#### Scenario: The expiry line is emitted on a second path
- **WHEN** a code path other than deadline expiry emits the same line
- **THEN** that is a defect: absence of the line must remain equivalent to "the handler was released
  because the work completed"

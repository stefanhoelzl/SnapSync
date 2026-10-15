# Proposal

## Why

On iOS 26.1 and later, two uploaders share a member's photos: the app's own, and iOS's background uploader, which the
app registers from join to leave. The integration suite cannot reach the second one. The JVM host always composes an OS
without it, so its registration, its runs after a switch or a force-quit, its behaviour after access is narrowed, and
the overlap of the two uploaders are tested nowhere above the unit level. The specs also leave three outcomes of that
pairing unstated:

- that iOS's uploader keeps working after a force-quit (only the iOS 18 half has a scenario);
- that a photo picked up by both uploaders still reaches the others once;
- that narrowing access after joining with full access keeps photos outside the selection private, even though the
  registration made under full access survives the change and iOS still runs its uploader.

## What Changes

- **Spec:** three new scenarios state those outcomes. No requirement's text changes; each requirement is restated only
  to carry its new scenario.
- **The JVM host can be an OS with iOS's uploader.** It becomes a durable device fact the operator sets, applied at the
  next launch. Off by default, so every existing test keeps today's OS.
- **The mocked registry follows the photo library's current grant.** It refuses registration and deregistration under
  limited access, as measured, and keeps a registration made under full access through a downgrade.
- **A force-quit the simulated OS can play.** The app's process dies, its background transfers are cancelled, and iOS
  launches it for nothing until the member opens it, while iOS's uploader keeps being invoked.
- **One new contract clause:** a registration made under full access still reads as registered under limited access,
  recorded on the SE2.
- **Integration tests** claim the new scenarios and the three existing ones the handoff named:
  - "Uploads work below iOS 26.1", re-claimed: the current claim observes only a scheduled wake;
  - "Reopening after a force-quit resumes uploading";
  - "iOS's uploader follows a switch".
- **Coverage:** raise the integration-coverage floor of every module this moves, or delete its exception.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `delivery`:
  - "A force-quit pauses the app's own background uploading only until the next opening" gains the iOS 26.1 scenario.
  - "A photo is never lost on the way" gains the two-uploaders scenario.
- `photo-access`: "Limited access is a working membership whose scope is the selection" gains the narrowing-after-join
  scenario.

## Impact

- **Mocks** (`:adapter:generic:mock`):
  - `ExtensionRegistryMock`: the OS fact, a record cell that always exists, and grant-following refusals;
  - `MockDevice` wiring;
  - `MockState`, which persists the fact;
  - `UploadSessionMock`: a force-quit's cancellation.
- **Typed services** (`:test:levers`):
  - `extension-registry` gains `carried(on)`;
  - `played-os` gains `forceQuit()`;
  - one new world event, `ForceQuit`.
- **Rig** (`:test:rig`):
  - the JVM host's reported upload tier follows the fact;
  - deliveries to the app are held after a force-quit;
  - the levers are dispatched on every host where their systems are mocked.
- **Control** (`:test:control`): a `given` step for the OS fact.
- **Contracts** (`:test:contracts`):
  - one `ExtensionRegistryContract` clause and its state;
  - a re-recorded `UploadExtensionRegistry@IOS_DEVICE_APP.LIMITED.rec` (SE2 lock).
- **Integration tests** (`:test:integration`): new tests, and the `HeartbeatCadenceIntegrationTest` claim re-pointed.
- **Production code:** no change expected. Any defect a new test finds is fixed in this change and named in its design.

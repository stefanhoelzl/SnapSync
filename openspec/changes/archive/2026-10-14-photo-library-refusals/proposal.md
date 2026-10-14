# Proposal

## Why

When the photo library answers "no", what the member sees depends on the app, yet the integration suite cannot
reach most of those answers. Four scenarios rest on such an answer and no test claims them:
- event-album: "A placement fails" and "A renamed album keeps receiving photos";
- photo-access: "Opening the app does not prompt" and "A refused member is taken to Settings".

A received photo the phone cannot open has no scenario at all, although the app already has a deliberate answer to
it. Where the library mock does answer "no", it does so in states no phone was measured in:
- it creates albums and hands out a change token to a process with no photo grant, which a real library refuses;
- it fails an undecodable import by a switch rather than by the file;
- it ignores the album an import names on iPhone, where a received photo is filed into the event album in the same
  commit that saves it.

## What Changes

- **receiving-photos:** one new scenario. A photo the member's phone cannot open is not saved; every other photo still
  arrives, and receiving does not wait on it.
- **The library mock follows the platform:**
  - With no photo grant it creates no album and answers no change token. This is what the no-grant contract clauses
    measured; the two switches that stood for it go.
  - It judges an import by the file's bytes. The "every import is undecodable" switch goes; a test has a download bring
    a file that is not an image through the existing download lever.
  - On iPhone it files an import into the album the import names, and saves the photo anyway when that album no
    longer exists.
  - An iPhone album the member renames keeps its identity.
- **New contract clauses, each backed by a real platform:**
  - a renamed iPhone album keeps its identity;
  - an import into an album files the photo there in the same commit;
  - an import into an album that no longer exists still saves the photo.
- **Five integration tests,** written in the event-waiting `behaviour` form: the four scenarios above and the new
  receiving-photos scenario.
- **Coverage exceptions** are raised wherever the new tests move a module's measurement.

No new operator lever is added. Every state is reached through the grant, the bytes an existing download lever
brings, or the existing album levers.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities
- `receiving-photos`: the requirement "Other members' photos arrive in the photo library automatically" gains what
  happens to a photo the phone cannot open.

## Impact

- **Spec:** `openspec/specs/receiving-photos/spec.md` gains one requirement sentence and one scenario. No other spec
  changes. The four existing scenarios are only claimed.
- **Mocks** (`:adapter:generic:mock`): the photo library mock (grant-derived refusals, decoding by bytes,
  import-time album filing, iPhone album rename) and its contract bindings.
- **Contracts** (`:test:contracts`): new reader and import clauses and their observation hooks. They run live on the
  iOS simulator app in CI's `journeys (ios)` and on the Android emulator where the platform has the state; there is
  no Linux host for them.
- **iOS simulator-app bindings** (`:adapter:ios:app-only` rig source set) and the Android device-test bindings: the
  hooks the new clauses need.
- **Typed services** (`:test:levers`, `:test:rig`): `renameAlbum` accepting iPhone albums. Nothing else on any face
  changes.
- **Integration suite** (`:test:integration`): the five tests. They use claim-photo-access's access-line tap and its
  dialog event.
- **Coverage** (root build file): the exceptions of the modules the tests move.
- **The app's own code** is not expected to change. A test that shows otherwise is reported, not specified around.

# Design

## Context

See proposal.md for the why. This is phase ⑩ of the integration-coverage programme (design session
`int-test-coverage`). The handoff's plan of three operator levers was overturned on checking the tree. What
follows is the plan the user settled, item by item.

The current mechanics that matter:

- **The library mock's three refusal switches** (`PhotoLibraryMock.kt`, `LibraryState`):
  - `refusesWrites` makes `createAlbum` answer null (`InMemoryGallery`);
  - `withholdsToken` makes `changeToken` answer null;
  - `undecodable` makes every import fail with its file consumed.

  No operator reaches them; only the mock's contract bindings set them.
- **Two of those states were measured only with no grant.** The real hosts reach `REFUSING_WRITES` (GalleryReader)
  and `TOKEN_WITHHELD` (Gallery) only in the iOS simulator's test executable. That process has no bundle id, so it
  holds no grant (`PhotoKitNoGrantContractTest`, both iOS adapter modules). No host has ever shown a *granted*
  library refusing an album or withholding the token. The mock nonetheless creates albums and answers a token
  under `NOT_DETERMINED` and `DENIED`.
- **Neither refusal is observable by a member:**
  - The token's only consumer is `WalkMemo`: without a token it walks the library again, and a refused process
    never walks.
  - `AlbumCoordinator` creates an album only under usable access, so the app never reaches a refused creation.
- **An undecodable import is already handled.**
  - The library consumes the file at ingest (`AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED`, live on the iOS simulator
    app and the Android emulator, both staging `PhotoLibrary.notAnImage`).
  - `DownloadController` then settles the row `UNIMPORTABLE`, logs it at error level and never retries.
  - The status counts leave such a row out of "still arriving" (`DownloadStore.sq`, design D8 of the download
    record), so the screen does not hang on it.
- **On iOS a received photo joins the event album in the same commit that creates it** (`IosPhotoLibraryImporter`).
  An album that no longer resolves files nothing and fails nothing. The mock ignores `request.album` for a
  collection library. No contract clause covers importing into an album at all.
- **The app addresses its album by the id it stored, never by title** (`AlbumCoordinator`, `AlbumMapService`). The
  `renameAlbum` lever refuses collection albums. No clause shows that an iPhone album keeps its id through a
  rename.
- **The only placement failure a contract measures** is an add to an album that no longer exists
  (`ADD_TO_A_MISSING_ALBUM_FAILS_AND_CREATES_NOTHING`). On a collection library the app keeps the stored id after
  the member deletes the album, so `deleteAlbum` reaches that failure on the next placement. (Found in apply: the
  mock hid a deleted collection album only from `albumsById`; it still took adds and listed its photos. D3 makes it
  absent everywhere.)
- **claim-photo-access (merged) supplies the dialog's two pieces:**
  - `WorldEvent.LibraryAccessAsked`, told when `requestAccess` raises the dialog while access is undecided;
  - the behaviour step `tap(UiIntent.RequestPermission)`.

## Goals / Non-Goals

**Goals:**
- Every library state a test enters is one a real platform was measured in, by a clause on a real host.
- The four existing scenarios and the new receiving-photos scenario are each claimed by one `behaviour` test that
  waits on events.

**Non-Goals:**
- **No operator levers for refusals.** The faces stay as they are, apart from a new photo kind and `renameAlbum`
  accepting collections.
- **Android's shapes** (⑧): folder albums, a renamed folder album not followed, Android's second dialog. The new
  import clauses run on the Android emulator only where its adapter has the state.
- **The claim phases 18–21:** no other scenario is claimed here.
- **Backend content events.** Each test that needs "this photo was stored" uses a single photo, so the operation
  alone identifies it.

## Decisions

### D1: The refusals follow the grant, not a switch

**Decision:**
- Under `NOT_DETERMINED` or `DENIED`, the in-memory library answers `createAlbum` with null and `changeToken` with
  null.
- `refusesWrites` and `withholdsToken` are removed.
- The contract bindings enter `REFUSING_WRITES` and `TOKEN_WITHHELD` by setting a no-grant state.

**Why:** that is the one condition a real host was measured in. A switch would let a test prove something about a
granted library that no phone has shown.

**Rejected:**
- *Operator levers `refuseAlbumWrites` and `withholdChangeToken`* (the handoff's plan): they model a granted library
  that refuses, which is unmeasured.
- *Keeping both the levers and the grant rule:* this only makes sense once a granted refusal (a full iCloud, a
  restricted profile) is measured.

### D2: Decoding is the file's, not the library's

**Decision:**
- The in-memory library reads each import's staged files through the device's own file system mock
  (`FileSystemMock`, resolving the `mem:/<area>/` locations it hands out). It fails the import as undecodable,
  with the files consumed, exactly when a file's bytes are the library's "not an image" bytes.
- The `undecodable` switch is removed.
- The mock binding of `AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED` stages `PhotoLibrary.notAnImage`, as the real
  bindings do. The clause therefore checks the mock's decoding rule against the same fixture.
- A test gets such a file to the library through the existing `downloadSession.finish(…, base64Bytes = …)`, the
  operator's choice of what a transfer brought. On the JVM host the mocked download never fetches from the backend:
  every receive test's transfer brings a stand-in JPEG, whatever the backend holds. The file then travels the app's
  own staging → import path.

**Why:** an undecodable file is a property of the file. With it, a test can mix an unreadable photo with an ordinary
one.

**On the rule:** exact equality with the fixture, not "is a JPEG". The fixture is the one file every real host was
measured to reject; a broader rule would have the mock decide what a real decoder does, which nothing measured.

**Plumbing:**
- The mock module cannot see `:test:contracts` from main code, so it declares the bytes itself.
- The commonTest binding asserts they equal `PhotoLibrary.notAnImage`.
- `MockDevice` hands the library a read over `disk`; plumbing stays off the face.

**Rejected:**
- *A one-shot lever `undecodableNextImport`:* a lever where a file can do the job.
- *`PhotoKind.NOT_AN_IMAGE` on `foreignDevice`* (proposed, then dropped in apply): a new operator input that, on the
  JVM host, never reaches the library, because the mocked download does not bring the backend's bytes. The existing
  download lever already chooses what a transfer brings.
- *Keeping "every import":* it cannot show the others still arriving.

### D3: An import files into the album it names, as on iPhone

**Decision:**
- Under `COLLECTION` the in-memory import adds the created asset to `request.album` in the same step that creates
  it, and tells `LibraryJoinedAlbum`.
- An album that no longer exists files nothing; the photo is still created and the import succeeds.
- A collection album the member deleted is absent everywhere — adds fail, it lists no photos and no import is filed
  into it — as `ADD_TO_A_MISSING_ALBUM_FAILS_AND_CREATES_NOTHING` measures; before, the mock hid it only from
  `albumsById`.

**Backed by two new `GalleryImportContract` clauses:**
- `AN_IMPORT_INTO_AN_ALBUM_FILES_IT_THERE`;
- `AN_IMPORT_INTO_A_MISSING_ALBUM_STILL_SAVES_IT`.

To support them, the subject's `ImportedLibrary` gains two hooks: create an album, and say whether an album holds
an asset.

**Hosts:**
- live on `IOS_SIM_APP` (the rig source set's PhotoKit bindings);
- on `ANDROID_EMU` where its folder albums give the same answer; otherwise `Unreachable`, with the reason.

**Why:** "new event photos are still placed in it" and "saved into the library as usual" are about *received*
photos on iPhone, and the mock contradicted iOS on exactly that path.

### D4: A renamed iPhone album keeps its identity

**Decision:**
- `renameAlbum` accepts a collection album: it changes the title and nothing else. A folder album keeps today's
  move-to-new-folder behaviour.
- The GalleryReader clause `A_RENAMED_COLLECTION_KEEPS_ITS_ID` backs it:
  1. create an album and rename it through the binding's own hook (`SeededLibrary` gains a rename handle; the
     member's rename is not a port operation);
  2. `albumsById` resolves the same id, under the new title;
  3. an add to it answers `Ok`.
- It runs live on `IOS_SIM_APP`, where the binding renames through PhotoKit's collection change request, the same
  change Photos makes.

### D5: The five tests

Each test below is a `behaviour { given / whenever / then }`.

**event-album, "A renamed album keeps receiving photos":**
- given: a joined member with the album on, and its album created (`LibraryAlbumCreated`);
- whenever: `renameAlbum(album, "…")`, then another member shares a photo;
- then: `LibraryJoinedAlbum` for the same album id and that photo's asset.

**event-album, "A placement fails":**
- given: a member with the album on, then `deleteAlbum`;
- received half: another member's photo arrives. Expect `LibraryAssetCreated` and `never` a `LibraryJoinedAlbum`.
- own half: the member takes a photo. Expect its transfer to be created and, once it lands, the app publishing what
  it shares (`BackendReceived(publishManifest)`). The test has one photo, so the publish is that photo's. The byte
  route an OS upload lands on tells the world stream nothing, and adding an event there is the backend content
  events' question, so the test does not wait on it.
- Whether the halves are one test or two is decided in apply.

**photo-access, "Opening the app does not prompt":**
- given: a joined member, access undecided;
- whenever: the app comes to the foreground;
- then: `never<LibraryAccessAsked>` until `Settled`;
- then a second act: `tap(UiIntent.RequestPermission)`, and `LibraryAccessAsked` arrives.

**photo-access, "A refused member is taken to Settings":**
- given: access `DENIED`;
- whenever: `tap(UiIntent.RequestPermission)`;
- then: `SettingsOpened`, and `never<LibraryAccessAsked>`.
- iOS shows no dialog to a refused process whether or not the app asks. The Android twin, where a second request
  does show one, is ⑧'s.

**receiving-photos, "A photo the phone cannot open":**
- whenever: another member shares `A` and `B`; `A`'s download brings an ordinary photo and `B`'s the "not an image"
  bytes;
- then: `LibraryAssetCreated` for `A`, `never` one for `B`, and the screen reaches up to date.

### D6: Coverage

**Expected to move:**
- `:domain:feature`: the `UNIMPORTABLE` branch of `DownloadController`, and `AlbumCoordinator`'s failed add;
- `:domain:services`: `GalleryImporter`'s failure path.

`WalkMemo`'s no-token fallback probably does not move, since a refused process never walks.

**Rule:** each moved `integration(…)` exception is raised to the lowest of the runs, rounded down. Exceptions never
fall.

**In apply:** one full run measured every module at or above its exception; the repeat runs the rule needs were
stopped by the machine running low on memory. By the user's decision the exceptions stay as they are — a raise from a
single run risks a flaky gate, and leaving an exception is always allowed.

## Risks / Trade-offs

- **[An existing test relied on albums or a token without a grant]** → D1 changes it. The build shows it, and a
  failure is reported as a finding, not papered over.
- **[An existing test relied on a collection import never joining the album]** (D3) → the same: the build finds
  it. `placeReceived` adding again is a no-op, since `LibraryJoinedAlbum` is told only for a new member.
- **[The new clauses cannot run on Linux]** → they are verified by CI's `journeys (ios)` and Android jobs at ship
  time. A clause that fails there sends the change back before merge.
- **[`ContractCoverageTest` needs every clause proven on a real host]** → `IOS_SIM_APP` is that host for all three
  new clauses. Before CI runs, the Linux build does not prove the real-host half.
- **[Scenario wording]** → "is shown as up to date without it" relies on the status counts' exclusion of
  unimportable photos. The test proves it; if it does not hold, that is a finding.

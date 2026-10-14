# Tasks

## 1. Refusals follow the grant (D1)

- [x] 1.1 In `InMemoryGallery`, answer `createAlbum` and `changeToken` with null under `NOT_DETERMINED`/`DENIED`;
  remove `refusesWrites`/`withholdsToken` from `LibraryState`. Verify: `:adapter:generic:mock:jvmTest` passes.
- [x] 1.2 In `PhotoContractBindingsTest`, enter `REFUSING_WRITES` and `TOKEN_WITHHELD` through a no-grant library
  rather than the switches. Verify: the GalleryReader and Gallery mock bindings pass both clauses.
- [x] 1.3 Run `./gradlew build`. Any existing test that depended on an album or a token without a grant is reported
  to the user as a finding before going on. Verify: the build is green, or the finding is reported.

## 2. Decoding by bytes (D2)

- [x] 2.1 Give the library mock a read of staged files over `MockDevice.disk` (resolving the `mem:/<area>/`
  locations). Fail an import whose file bytes are the mock's "not an image" bytes as undecodable, with the files
  consumed. Remove the `undecodable` switch. Verify: `PhotoLibraryImportTest` covers an undecodable and an ordinary
  file.
- [x] 2.2 Make the mock binding of `GRANTED_INVALID_STAGED` stage `PhotoLibrary.notAnImage`, and assert the mock's
  bytes equal it. Verify: `AN_UNDECODABLE_FILE_FAILS_AND_IS_CONSUMED` passes on the mock binding.
- [x] 2.3 No face change: a test brings the "not an image" bytes through the existing
  `downloadSession.finish(…, base64Bytes = …)`. (The `PhotoKind.NOT_AN_IMAGE` first planned here was dropped, see D2.)
  Verify: task 5.1's test receives such a photo and the library creates nothing for it.

## 3. Import into an album (D3)

- [x] 3.1 Add the album hooks to `ImportedLibrary`, and the clauses `AN_IMPORT_INTO_AN_ALBUM_FILES_IT_THERE` and
  `AN_IMPORT_INTO_A_MISSING_ALBUM_STILL_SAVES_IT` to `GalleryImportContract`, with their port-grid cells. Verify:
  `:test:contracts` compiles, and `ClauseCoversTest` accepts the cells.
- [x] 3.2 Under `COLLECTION`, file an in-memory import into `request.album` at creation and tell `LibraryJoinedAlbum`;
  a missing album files nothing and the import succeeds. Bind both clauses on the mock. Verify:
  `:adapter:generic:mock:jvmTest`.
- [x] 3.3 Bind both clauses on `IOS_SIM_APP` (`:adapter:ios:app-only` rig source set) and, where its folder albums
  give the same answer, on `ANDROID_EMU`; otherwise answer `Unreachable` with the reason. Verify:
  `./gradlew compileIosMainKotlinMetadata` and the Android device-test compile; the live run is CI's `journeys (ios)`
  and Android jobs.
- [x] 3.4 Run `./gradlew build` and report any existing test that relied on a collection import never joining the
  album. Verify: the build is green, or the finding is reported.

## 4. A renamed collection keeps its identity (D4)

- [x] 4.1 Add a rename handle to `SeededLibrary` and the clause `A_RENAMED_COLLECTION_KEEPS_ITS_ID` to
  `GalleryReaderContract`. Bind it on the mock and on `IOS_SIM_APP` (PhotoKit's collection change request). Verify:
  the mock binding passes, and the iOS metadata compile is green.
- [x] 4.2 Let `renameAlbum` accept a collection album, changing only its title, and update the `LibraryLevers` KDoc.
  Verify: a mock test renames a collection and an add to it still lands.

## 5. The integration tests (D5)

- [x] 5.1 Write "A photo the phone cannot open" (receiving-photos) as a `behaviour` test with `@Verifies(…, scenario
  = …)`. Verify: it passes on the JVM host.
- [x] 5.2 Write "A renamed album keeps receiving photos" (event-album). Verify: it passes.
- [x] 5.3 Write "A placement fails" (event-album), covering the received and the own-photo halves. Verify: both
  pass; the own half waits on the upload's creation and then the app's manifest publish, with a single photo in play.
- [x] 5.4 Write "Opening the app does not prompt" and "A refused member is taken to Settings" (photo-access) over
  `LibraryAccessAsked` and `tap(UiIntent.RequestPermission)`. Verify: both pass.
- [x] 5.5 Report to the user any test that shows the app or a scenario wrong, before changing either. Verify: no
  silent re-specification.

## 6. Coverage and the gate (D6)

- [x] 6.1 Read each moved module's integration measurement. One full run measured every module at or above its
  exception (`:domain:feature` 91.40/73.97, `:domain:services` 83.10/61.01, `:domain:ports` 90.75); the repeat runs
  were stopped by the machine running low on memory, so — by the user's decision — no exception is raised from a
  single run. Verify: the coverage checks pass, and no exception fell.
- [x] 6.2 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`; regenerate `architecture/` if it went
  stale. Validate the change with `npx --yes @fission-ai/openspec@1.13.2 validate photo-library-refusals --strict`.
  Verify: everything is green.

# Tasks

## 1. Pin the guarantee the change rests on

- [x] 1.1 Extend the api test "byte PUT → a missing or empty filename" (`api/test/v2.test.ts`) so that it checks the event-scoped upload and
  the event-less upload both answer `400 missing filename` for an absent and for an empty `filename`, and that
  neither writes a `resources` row. Verify: `cd api && deno task test` passes, and the test fails if `minLength: 1` is
  removed from `api/openapi.yaml`.

## 2. The non-empty sender filename

- [x] 2.1 Add `SenderFilename` to `:domain:domain` `receiving/`: a value class whose only constructor is
  `SenderFilename.parse(text): SenderFilename?` (`null` for `""`; named `parse` after `AssetId.parse`), with KDoc citing this change. Verify: a unit test in
  `:domain:domain` for empty → `null`, and for a non-empty name kept verbatim.
- [x] 2.2 `ReceivedPhotoName.mark` takes a `SenderFilename`. Delete the empty-name branch (`ifEmpty { resourceKey }`,
  the `bare.isEmpty()` arm, `stemOf`'s `original.isEmpty()` arm), and drop `resourceKey` if nothing else uses it.
  Rewrite the KDoc: the "never enriched" sentence goes. Add to the class KDoc that `tokenOf`'s start-of-name match
  reads a form earlier builds wrote into libraries, which never age out, so the read is permanent (D4). Verify:
  `ReceivedPhotoNameTest` loses `an_unenriched_row_is_named_by_its_mark_and_the_keys_extension`.
  a new `the_bare_mark_earlier_builds_wrote_still_reads_back_its_token` reads the literal `"snapsync-fim54ck2vz.heic"`;
  `the_role_token_never_reaches_the_library` goes (no key reaches `mark` any more), and the
  golden token values are unchanged. `:domain:domain` tests pass.

## 3. Refuse an empty name where it enters

- [x] 3.1 `UnionResource.originalFilename` becomes a `SenderFilename`. `EventUnion.photo` parses each resource's
  filename alongside the ids, so a photo with an empty one yields no photo and the page is `UnionRead.Unavailable`
  (D2). Extend `paged`'s KDoc to name an empty filename. Update `ReceivingPlans` (writes `.value` into
  `downloads.db`) and the fixtures (`domain/.../ReceivingFixtures.kt`, `test/data/.../ReceivingFixtures.kt`). Verify:
  a new `EventUnionTest` case — a page with one resource whose filename is `""` reads as `Unavailable`, and an
  ordinary page still reads `Ok`.
- [x] 3.2 `StagedResource.originalFilename` becomes a `SenderFilename`. When the next claimable photo has a stored
  resource with an empty name, `stagedResources` / `ImportQueue` settle it unimportable through the guarded
  `settledUnimportable` write, release its staged bytes and log the ref and reason through `Log`. It is never
  claimed for the library (D3). Verify: a new `ImportQueueTest` case seeds a fully staged photo whose
  `originalFilename` is `''`. Its row ends `UNIMPORTABLE`, its staged files are released, no `StagedDownload` is
  handed out, and the next photo is still claimed.
- [x] 3.3 `LibraryImport.marked` passes the `SenderFilename` through. Verify: `LibraryImportTest` passes unchanged in
  its assertions (only fixtures move to the new type).

## 4. The renamed requirement

- [x] 4.1 Move every `@Verifies(…, requirement = "Received photos keep full fidelity", …)` to "Received photos arrive in
  full fidelity" (`FullStackIntegrationTest`, `ReceivingIntegrationTest` and `test/journeys/.../Journeys.kt`; 7 sites),
  and the cite at `docs/testing.md:1396` (D5). Verify: `grep -rn "keep full fidelity" test docs domain adapter app`
  prints nothing.

## 5. Integration

- [x] 5.1 Confirm nothing else names the removed scenario or its behaviour. `grep` for "without a sender filename",
  "never enriched" and `mark("` across `domain`, `test`, `adapter`, `docs` and `app` finds only this change's
  artifacts and the archived decision record. Verify: the grep output.
- [x] 5.2 `./gradlew build` (with `deno` on PATH) and `./gradlew compileIosMainKotlinMetadata` are green, and
  `./gradlew :test:architecture:verifiesReport` no longer lists "A photo without a sender filename is still marked"
  as unclaimed. Verify: both commands' output.

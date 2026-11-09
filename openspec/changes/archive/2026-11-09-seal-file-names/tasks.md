# Tasks

## 1. The file-name record (both format halves and the vectors)

- [x] 1.1 Add the file-name key derivation to `SealedMetaFormat`: label `snapsync/file-name/v1/<event>/<device>/<asset>/<role>`, built from a `FileBinding`. Verify with `SealedMetaFormatTest` cases for the derivation and for the binding (another binding derives another key).
- [x] 1.2 Add `FileNameCipher` (data/crypto) with `sealFileName` and `openFileName`, sharing the envelope with `MetaCipher` through `SealedRecords` and reusing `MetaRecord`; an empty opened name is a damaged record. Verify with the real-crypto vector test (`:adapter:generic:app` `SealedMetaFormatTest`): exact envelopes and round trip, and the reject vectors (another photo, the event's record, another key, an empty name) do not open.
- [x] 1.3 Add `sealFileName`, `openFileName` and the derivation to `api/src/sealed-meta.ts`. Verify with `api/test/sealed-meta.test.ts`.
- [x] 1.4 Extend `scripts/sealed-meta-vectors.py` and regenerate `test/vectors/sealed-meta.json` with file-name vectors (binding, key, envelope, name). Verify that both `SealedMetaFormatTest` (Kotlin vectors) and the TypeScript vectors test open them.

## 2. The service and its mirror

- [x] 2.1 In `routes/encrypted-upload.ts` / the byte routes, refuse an upload into an encrypted event whose `filename` is not a sealed envelope (`400 invalid filename`) on both the event-scoped and the event-less route, before a byte is stored. Verify with `api/test/encrypted-upload.test.ts` cases (an unsealed name is refused; a sealed one lands and is served unchanged; a plain event still takes a readable name).
- [x] 2.2 `openapi.yaml`: manifest resource `key` and `filename` optional (ignored); the upload's `filename` documented as the sealed record in an encrypted event; the union's `key` documented as kept for older builds. Regenerate the wire (`:core:wire`) and the api's generated types. Verify that `deno task check` and `./gradlew :core:wire:build` pass.
- [x] 2.3 Mock backend (`BackendRouter`/`BackendMock`): the same shape check, the union's filename taken from the upload, not the manifest, and a manifest without `key`/`filename` accepted. Add `BackendEventClauses`/`BackendListingClauses` contract clauses for the refusal and the served sealed name, run against both the api and the mock. Verify with `./gradlew :test:contracts:jvmTest` (or the contract task the module defines).
- [x] 2.4 `docs/architecture.md`: the sealed metadata section gains the file-name record; the HTTP table's PUT row names the shape check, the manifest row the optional fields, and the union row the `key` kept for older builds. Verify by reading the rows against the code.

## 3. The uploading phone

- [x] 3.1 `UploadRequests.first`/`retry` take the upload's `UploadSeal`: plain → the readable name as today; sealed → `FileNameCipher.sealFileName(order.key, order.binding, name)`. `SessionTier` and `OsQueueTier` ask `UploadSealing.sealFor` first and pass the seal (a withheld seal builds no request). Verify with `SessionTierTest`/the OS queue tier tests: an encrypted event's request query carries an envelope that opens to the name; a plain event's carries the name.
- [x] 3.2 `ManifestPublication` stops sending `key` and `filename`. Verify with the manifest publication test asserting the body.
- [x] 3.3 Wire `FileNameCipher` into `UploadRequests` in `:composition:app` and the extension. Verify with `./gradlew :core:composition:app:build`.

## 4. The receiving phone

- [x] 4.1 `ServedNames` (data/crypto) reads a membership's served names (plain → as served; encrypted with its key kept → opened per photo; key not readable or not kept → unavailable), asked for the membership a read is for. `EventUnion.read` takes that membership and opens names as D6 says, dropping a photo whose sealed name does not open with a Warn line, and derives each resource key with `UploadKeyText.of(UploadKey.of(asset, role, name))` instead of reading the union's `key`. Verify with `EventUnionTest`: plain unchanged, sealed names opened and keys derived from them, one unopenable photo dropped while the others are kept, and no key kept → unavailable with nothing asked.
- [x] 4.2 `DeviceListing.stored` takes the membership and keys each item from its opened name, leaving out an item whose sealed name does not open; `LoadShareSet` (the join, before entering) and `SettleStoredUploads` (the foreground) pass theirs; `PlanReceiving`, `HoldsEverything` and recognition pass theirs to the union. Wire `ServedNames` in `ServicesGraph`. Verify with the listing, settle and share-set tests and `./gradlew :core:composition:app:build`.
- [x] 4.3 `EncryptedEventIntegrationTest`: a member of an encrypted event receives a fellow member's photo named "IMG_4471.HEIC", shared with its name sealed, and it arrives under that name with its mark. Verify that the test passes.
- [x] 4.4 The test equipment shares names as a real build does: the mock's `EventKeyLedger` seals and opens a photo's name for the events its device created, the rig's `BackendReach` seeds members with sealed names and lists keys from opened ones, journey `Member` uploads sealed names, and the desktop inspector deposits one. Verify with `./gradlew :test:rig:build :app:desktop:build`.

## 5. The event page

- [x] 5.1 `site/src/lib/event-key.ts`: open a web list's names with the key and each resource's binding, dropping any asset with a name that does not open. `join.astro` applies it in `readList` for an encrypted event. Verify with `site/scripts/event-key.test.ts`.
- [x] 5.2 `site/src/lib/zip-names.ts`: remove the `photo`/`video.MOV` fallbacks; a resource without a name is skipped with its asset. Verify with an updated `site/scripts/zip-names.test.ts`.

## 6. Cleanups and policy

- [x] 6.1 `PENDING_CLEANUPS.md`: a new entry, "Union `key` and manifest `key`/`filename` for older builds", giving the what, where, why, the unlock (the "Re-enable encrypted creation" floor) and the removal steps; and the re-enable entry's returning Privacy Policy paragraph names photo names as stored encrypted. Verify by reading the entries.

## 7. Integration checks

- [x] 7.1 `./gradlew build` passes (including `compileIosMainKotlinMetadata`, detekt and the contracts against the real api).
- [x] 7.2 `cd api && deno task test`, and the site's tests, pass.

## 1. Domain: the naming rule

- [x] 1.1 Add `importFilename(originalFilename, resourceKey)` to `:domain` `model/`, documenting the PhotoKit default it exists to override and why the empty case falls back to the key
- [x] 1.2 Cover it in `commonTest` (JVM + `iosSimulatorArm64`): the published name wins; `""` falls back to the key; the result is never empty; no role token survives a named import; a Live Photo's two resources keep distinct names

## 2. iOS importer

- [x] 2.1 Thread each resource's name through the existing `mapNotNull`, so the per-resource loop carries `(type, path, filename)`
- [x] 2.2 Create a `PHAssetResourceCreationOptions` per resource with that name and pass it to `addResourceWithType` in place of `null`
- [x] 2.3 Record in the class KDoc that the name is explicit and what PhotoKit would otherwise pick

## 3. Harness honesty

- [x] 3.1 Apply the same `importFilename` in `:test:world`'s `FakePhotoLibraryImporter`, which used the raw published name and so modelled a behaviour production did not have
- [x] 3.2 Add a `FullStackIntegrationTest` case asserting an imported foreign photo carries the capturing device's filename through the real composed core

## 4. Spec and build

- [x] 4.1 Extend `photo-download`'s full-fidelity import requirement with the naming rule, its fallback, its placement, and the "no re-upload" property; add three scenarios
- [x] 4.1b Extend `harness-world-model`'s download-seam requirement: the fake importer names through the same shared rule, so the harness cannot be more correct than the device
- [x] 4.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 4.3 `./gradlew build` green
- [x] 4.4 `./gradlew compileIosMainKotlinMetadata` green (the Linux proxy for the iOS source sets — the only pre-merge check on the `PHAssetResourceCreationOptions` call)

## 5. Device verification (acceptance)

- [ ] 5.1 ssh-mac dev build, joined to an event with a foreign contributor: download a photo and confirm Photos shows the capturing device's filename, not the object key
- [ ] 5.2 Confirm a downloaded Live Photo still plays (both resources named, neither dropped)

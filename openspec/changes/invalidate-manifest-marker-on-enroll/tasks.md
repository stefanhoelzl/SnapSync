## 1. The seam

- [x] 1.1 Add `clearLastUploaded()` to `ports/DeviceManifestStore`, documenting *why* it exists (a second
      writer falsifies the record), not merely what it does
- [x] 1.2 Implement it in `IosDeviceManifestStore` (delete the file), `InMemoryDeviceManifestStore`, and
      any other impl the module set holds

## 2. The fix

- [x] 2.1 Give `ManifestDeviceEnroller` the `DeviceManifestStore` and clear the record after a
      **successful** register-only PUT (D3: never after a failed one)
- [x] 2.2 Wire the store through `compose/SnapSyncApp`'s `ManifestDeviceEnroller` construction
- [x] 2.3 Correct `LeaveEvent`'s doc claim about the manifest self-healing — it is true only with this fix

## 3. Tests

- [x] 3.1 `ManifestDeviceEnrollerTest`: a successful enroll clears the record; a failed enroll leaves it
- [x] 3.2 A regression test at the seam that actually broke: record a projection, enroll (as a re-join
      does), produce the **same** projection, and assert the PUT happened
- [x] 3.3 `./gradlew build` green; `compileIosMainKotlinMetadata` green (iOS impl compiles)

## 4. On device

- [ ] 4.1 Re-join an event this device has contributed to and confirm `device.json` lists its assets
      afterwards, rather than being empty

## 1. Lock orientation in `Info.plist`

- [x] 1.1 In `iosApp/iosApp/Info.plist`, narrow `UISupportedInterfaceOrientations` to a single entry: `UIInterfaceOrientationPortrait` (remove `LandscapeLeft` and `LandscapeRight`)
- [x] 1.2 Narrow `UISupportedInterfaceOrientations~ipad` to a single entry: `UIInterfaceOrientationPortrait` (remove `LandscapeLeft`, `LandscapeRight`, and `PortraitUpsideDown`)

## 2. Verify

- [x] 2.1 `./gradlew compileIosMainKotlinMetadata` still green (sanity; does not read the plist, but confirms no incidental breakage)
- [ ] 2.2 Manual: install on the physical iPhone SE2, rotate to landscape → UI stays upright portrait
- [ ] 2.3 Manual (best-effort): on an iPad or iPad simulator, rotate to landscape and 180° → UI stays upright portrait

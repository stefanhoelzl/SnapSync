## 1. Lock orientation in `Info.plist`

- [x] 1.1 In `iosApp/iosApp/Info.plist`, narrow `UISupportedInterfaceOrientations` to a single entry: `UIInterfaceOrientationPortrait` (remove `LandscapeLeft` and `LandscapeRight`)
- [x] 1.2 Remove the `UISupportedInterfaceOrientations~ipad` key (dead config once the app targets iPhone only)

## 2. Drop iPad as a target (TMS-90474)

- [x] 2.1 In `iosApp/iosApp.xcodeproj/project.pbxproj`, set `TARGETED_DEVICE_FAMILY = "1"` (iPhone only) in both the Debug and Release build configs

## 3. Verify

- [x] 3.1 `./gradlew compileIosMainKotlinMetadata` still green (sanity; does not read the plist/pbxproj, but confirms no incidental breakage)
- [ ] 3.2 Manual: install on the physical iPhone SE2, rotate to landscape → UI stays upright portrait
- [ ] 3.3 TestFlight upload of the resulting build succeeds (no TMS-90474 rejection)

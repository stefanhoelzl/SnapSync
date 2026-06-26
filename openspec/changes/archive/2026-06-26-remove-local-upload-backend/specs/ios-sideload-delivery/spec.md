## REMOVED Requirements

### Requirement: Optional compile-time upload host via workflow_dispatch

**Reason**: This requirement duplicated the compile-time upload-host contract that the `ios-ci`
capability already owns ("Compile-time edge host default and override"). The host is baked in the
**shared** archive step (the merge gate), and the default applies to **all** delivery channels —
including the TestFlight build on `main`, which is not a sideload concern — so the contract belongs
to the workflow capability (`ios-ci`), not to sideload delivery. The sideload IPA simply inherits
whatever host the shared archive baked.

**Migration**: See `ios-ci` → "Compile-time edge host default and override" for the default host
(the `Config.xcconfig` fall-through on an empty/absent input) and the HTTPS-only `upload_host`
override (a non-https value fails the run). No behavior is lost — the contract is consolidated into a
single owner, not dropped.

# macOS verification (task 9.6) — green

Run on a dispatched `macos-26` runner via the ssh-mac loop, 2026-08-25. macOS 26.5.2, Xcode 26.6.

```
./gradlew iosSimulatorArm64Test      → BUILD SUCCESSFUL in 4m 19s
```

Every macOS-only test executed; **0 failures, 0 skipped** across 138 classes. The ones this change
turns on:

| test | tests | note |
|---|---|---|
| `PhotoKitCandidateSourceTest` | 8 | includes the new `DenyAll` zero-row predicate assertion |
| `NativeLedgerStoreTest` | 32 | the native ledger driver against the new `absent` column |
| `InMemoryLedgerStoreTest` | 32 | the shared store contract, mark scenarios included |
| `UploadCycleTest` | 62 | gate reorder, marking, manifest suppression, narrow→widen round trip |
| `SelectionPolicyTest` | 14 | the collapsed rule-list type |
| `NarrowingRetractionTest` | 3 | narrowing retracts at the manifest; widening re-lists free |
| `PhotoKitSmokeTest` | 3 | the PhotoKit glue still runs on the simulator |

What this does **not** establish: that PhotoKit's query engine returns zero rows for the `DenyAll`
predicate against a real library. `predicateWithFormat` accepting the clause and the translator emitting
it is all a simulator can show. That is task 10.1, and it is the same distinction this codebase draws
between what an API declares and what a device does.

---

# A trap in the `ssh-mac-build` runbook, hit and worked around

**The re-sign step reads `TEAM_ID` and `ASSOCIATED_DOMAIN` from `Config.xcconfig`. They are not there any
more.** They moved into the generated `Deployment.xcconfig` (capability `deployment-configuration`), so
the runbook's `awk` matches nothing and both come back **empty** — and the script signs anyway:

```
TEAM= DOMAIN= ID=B5812B85FB44376BC865144F59DCB3F80CA4980E
... SIGN OK
```

`SIGN OK`. The wildcard guard passed. `codesign -v` passed. And the IPA was poison: with `TEAM` empty,
`$(AppIdentifierPrefix)` expands to a bare `.`, so the keychain group signs as `.app.snapsync.shared`
instead of `E9Z8BADH58.app.snapsync.shared`.

That is the failure the runbook itself documents as the worst kind. An explicit-group query
(`kSecAttrAccessGroup = <TEAM>.app.snapsync.shared`) is not satisfied by it, so device-identity reads
throw `errSecMissingEntitlement` (-34018); the launch coroutine logs rather than aborts, and the app
installs, launches, looks correct and is dead in the water with no device id — a value written once and
never rewritten.

**Why the existing guard did not catch it.** The wildcard guard is deliberately key-agnostic, so it
catches whichever wildcard key Apple adds next. But it only looks for `*`. A key that is *present and
wrong* — `.app.snapsync.shared` — contains no wildcard and sails through. The guard's blind spot is
exactly the case where a substitution silently produced garbage rather than a grant leaking through.

**Worked around** by reading the generated fragment and refusing to sign on empty:

```bash
CFG="$SRC/Configuration/Deployment.xcconfig"     # NOT Config.xcconfig
TEAM=$(awk -F= '/^TEAM_ID/{gsub(/[ \t]/,"",$2);print $2}' "$CFG")
[ -n "$TEAM" ] || { echo "TEAM_ID empty — refusing to sign"; exit 1; }
# …and afterwards, a POSITIVE check on the signed binary, not just the absence of a wildcard:
codesign -d --entitlements :- "$APP" | plutil -p - | grep -q "$TEAM.app.snapsync.shared" \
  || { echo "keychain group lacks the team prefix — refusing"; exit 1; }
```

Verified entitlements on the shipped IPA: `E9Z8BADH58.app.snapsync.shared`,
`E9Z8BADH58.app.snapsync`, `applinks:snapsync.stho.net`, `get-task-allow`, no wildcards.

**Suggested runbook fix** (not applied — the skill is outside this change's scope): point `build_ent` at
`Deployment.xcconfig`, and add the two refusals above. The second is the more general lesson — an
absence check (`no wildcard`) is not a presence check (`the right prefix`), and only the latter catches a
substitution that produced nothing.

---

# The rig build

Built `-configuration Debug` with `snapsync.rig=true` appended to the runner's `gradle.properties` — the
Xcode build phase invokes `./gradlew :app:ios:embedAndSignAppleFrameworkForXcode` with no properties, so
there is no xcodebuild-line way to pass it.

Confirmed linked in before installing anything: `RigServer` and `app.snapsync.rig.*` symbols present in
the app binary. A build without them is undriveable, and silently so.

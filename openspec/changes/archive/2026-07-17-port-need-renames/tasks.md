# Tasks — port-need-renames

## 1. Renames

- [x] 1.1 `git mv` every file whose basename embeds a renamed port (26 files)
- [x] 1.2 Tree-wide identifier substitution (types + embedding impl/fake/test/factory names),
      excluding `openspec/` (ceremony), `PLAN.md` (hand-edit), `architecture/` (regenerated)
- [x] 1.3 `LedgerStore` ports/-seat trial → model-purity gate red → reverted, seat kept in `model/`

## 2. Ride-alongs

- [x] 2.1 CLAUDE.md module rows/prose; app/ios/CLAUDE.md; `ios.yml` + entitlements comments
- [x] 2.2 PLAN.md forward-looking references + 3b row Δ-note (seat + `TransferNotify`)
- [x] 2.3 Beacon deletion-ledger regex → `class \w*Enrollment` (debt stays counted)

## 3. Spec deltas

- [x] 3.1 MODIFIED restatements for every requirement naming a renamed port (12 capabilities)
- [x] 3.2 RENAMED header in `ios-url-session-upload`
- [x] 3.3 `permission-gate` Purpose edited in place (deltas cannot carry Purpose)

## 4. Gates

- [ ] 4.1 `./gradlew build` green
- [ ] 4.2 `./gradlew compileIosMainKotlinMetadata` green
- [ ] 4.3 `./gradlew architectureDiagrams` regenerated, output committed
- [ ] 4.4 Beacon after = beacon before (85; Δ 0 on every law)
- [ ] 4.5 `openspec validate --specs --strict` green after archive

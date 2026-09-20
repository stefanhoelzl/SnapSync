## 1. The caption

- [x] 1.1 In `ui/screens/src/commonMain/kotlin/app/snapsync/ui/JoinedLayer.kt:58`, change the
      `AppQrCode` caption from `"Scan to join this event"` to `"Let someone else scan this to join"`.
- [x] 1.2 Rewrite the comment above that block: it currently justifies the caption as instructing
      "the person scanning it" (two audiences). State instead that both lines address the member, that
      a scanner reads their own camera rather than this card, and that the caption may name no noun
      the reader could be — which is why it is "someone else" and not "guests".

## 2. Tests

- [x] 2.1 Update the three literal assertions in
      `ui/screens/src/commonTest/kotlin/app/snapsync/ui/StatusScreenTest.kt` (`:376` absence,
      `:603` and `:665` presence) to the new string. These remain the mechanical pin now that the
      specs no longer quote it.
- [x] 2.2 `./gradlew build` — compiles all targets and runs the JVM tests, including
      `:ui:screens:jvmTest` (offscreen, no display needed) and the `detekt*Tier` ceilings.

## 3. Verification (apply phase)

- [x] 3.1 `grep -rn "Scan to join" --include='*.kt' --include='*.md' .` outside
      `openspec/changes/archive/` returns only the two main specs (which `sync` rewrites) — no Kotlin,
      no test. Archived decision records keep the old string: they are history and are not rewritten.
- [x] 3.2 `npx --yes @fission-ai/openspec@1.5.0 validate clarify-invite-qr-caption --strict`.

## 4. Deferred to the `sync` phase — NOT done during apply

These edit `openspec/specs/`, which is `sync`'s job, and each is a phase boundary the user opens.
Listed here so `sync` does not have to rediscover them.

- [ ] 4.1 Apply the `event-invite-qr` delta.
- [ ] 4.2 Apply the `desktop-test-harness` delta.
- [ ] 4.3 Fix the **purpose** line of `openspec/specs/event-invite-qr/spec.md`, which also quotes
      `("Scan to join this event")`. A delta cannot reach a Purpose section, so this must be edited by
      hand at sync or the spec will contradict its own requirement inside one file.
- [ ] 4.4 Replace the `Decision record: changes/archive/<this change>` placeholder in the delta with
      the real archived id once `archive` assigns it.
- [ ] 4.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — structure only; it does
      not read Kotlin, so it proves well-formedness, not truth.

## 5. Screenshots

- [x] 5.1 Push the branch, then `gh workflow run screenshots.yml --ref <branch>` (~11-19 min).
- [x] 5.2 `RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')` then
      `gh run download "$RID" -n screenshots-raw -D screenshots`.
- [x] 5.3 **Look at all six raws.** Confirm the new two-line caption reads well in light and dark and
      the taller card has not crowded the status line. Re-dispatch if a "Ready for Apple Intelligence"
      system notification landed in any capture — it cannot be detected automatically.
- [x] 5.4 Confirm the diff is confined to `in_sync-{light,dark}` plus `create`'s wall-clock region;
      `joining` should come back byte-identical. A diff anywhere else means the UI really moved.
      **Measured (run 35536202440):** `joining` byte-identical. `create` re-diffs in one band,
      y 966..1004 — the event date range, which derives from the capture instant. The runbook's
      "90x32px" is stale: the create screen was redesigned to render a full range, so the region is
      566x39. `in_sync` diffs only in the hero, y 1083..1875. No notification banner in any of the six.
      Incidentally, the committed `create-dark` had a dimmed, mid-transition status bar unlike every
      sibling; the new capture is fully rendered.
- [x] 5.5 `git add screenshots/ && git commit`. No store or site action follows — the listing derives
      from these raws at release time and `site/` on merge.

## 6. Ship

- [ ] 6.1 Open the PR with the `bug` changelog label.
- [ ] 6.2 `/ship`.

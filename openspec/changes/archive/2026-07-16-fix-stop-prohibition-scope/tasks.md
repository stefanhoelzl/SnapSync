## 1. Pin the contradiction before resolving it

The temptation is to "just fix the wording". Record first *which* artifact is wrong and how it is known,
because the losing side is three specs and a reader will want the receipt.

- [x] 1.1 Record the evidence in this change directory: `40a6ee2` created **both**
      `upload-lifecycle/spec.md` and `PhotoKitUploadProducer.kt` (`git log --follow` on each), so the
      prohibition and the code it forbids were authored together; that change's design **D1** tabulates
      PhotoKit `stop()` as "`enable(false)` + `clearRequested` + **clear cursor** *(the OS disable wipes
      in-flight jobs; this is the repair)*" and its **task 3.5** is ticked with the same words; its **D2**
      wrote the prohibition while reasoning only about the ledger ("Leave keeps the ledger… the PhotoKit
      tier already honors it"). D1 and D2 never met. This is an unnoticed collision, not a supersession —
      say so, so nobody re-opens it as one.
- [x] 1.2 Record the cost measurement rather than asserting harmlessness: that change's own task 6.4 shows
      `cleared cursor` → `discoverResources = 52 resource(s)` → `enumeration: 50 seen, **0 new**, 50
      already-uploaded` → **0 `createJob`**, backend **54 → 54 unchanged**. A cleared cursor costs one
      enumeration and re-uploads nothing.

## 2. Narrow the prohibition to what it defends

- [x] 2.1 `upload-lifecycle`: `stop()` SHALL NOT destroy **dedup state** (ledger rows, stored bytes). Carve
      the cursor out **conditioned on the invariant, not on the tier** (D2): a repair for damage the tier's
      own mechanism causes, and only where `COMPLETED` rows survive. Keep both halves — the first forbids
      clearing for tidiness (the app-driven wipe this prohibition was written to kill), the second is the
      safety property.
- [x] 2.2 Re-pin the four cursor scenarios in `upload-lifecycle` to the ledger and bytes, and add the two
      that state the carve-out and its limit. A carve-out with no scenario for what it still forbids is an
      unbounded one.
- [x] 2.3 `event-rejoin-reconciliation` + `sync-ledger`: drop the cursor from their prohibitions. **Every
      ledger clause stays exactly as strong.** If a ledger clause weakens anywhere, the edit is wrong —
      that is the sentence that stops a guest's camera roll reaching a stranger's event.
- [x] 2.4 Leave `ios-photokit-upload` **untouched**. It was right; editing it would be the same
      generalization running the other way. *(Verified byte-identical to `origin/main`.)*
- [x] 2.5 **The delta files were over-broad on the first attempt, and the sync caught it.** Writing an
      in-place `MODIFIED` means restating a whole requirement, and restating it from the parts you have
      read drops the parts you have not: the first drafts silently deleted `upload-lifecycle`'s transition
      table, its "No membership, no arm" three-valued-seam paragraph and `commonTest` mandate;
      `event-rejoin-reconciliation`'s entire `resetTo`/marker mechanics plus three scenarios; and two
      `sync-ledger` scenarios — none of which this change proposes touching, and one of which
      (three-valued seam) is itself a fix for a shipped bug. The sync preserved them and applied only the
      cursor substance, so the main specs are right; the deltas were then **regenerated from the synced
      main specs** and asserted byte-identical to them, so an archive that replaces wholesale cannot
      resurrect the deletion. A `MODIFIED` delta is a diff written as a whole — the whole is the hazard.

## 3. Make the code stop contradicting itself

- [x] 3.1 `UploadArm.kt`: the KDoc table at `:11` says PhotoKit `stop()` clears the cursor and `:22` says
      "`stop()` **never** clears the ledger or the cursor" — in one comment, eleven lines apart. Fix `:22`
      and `onLeave`'s "the discovery cursor … kept" claim (`:107`). Comments only; no behaviour changes.

## 4. Verify

- [x] 4.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`. Both should be no-ops for
      behaviour — if a test moves, something other than a comment changed and the change is wrong.
- [x] 4.2 `openspec validate --specs --strict`.
- [x] 4.3 Confirm the tree no longer contradicts itself: no spec forbids clearing the cursor while
      `ios-photokit-upload` requires it, and no ledger or stored-bytes prohibition was weakened
      (`git diff` the three specs and read every removed line).

## 5. Hand off what this does not do

- [x] 5.1 Record that **nothing mechanized the old prohibition and nothing mechanizes the new one**.
      `UploadArmTest:150` ("The seam exposes no way to clear the ledger or the discovery cursor") tests the
      *seam's shape* over a `FakeProducer` — it passed while the code violated the rule and would pass if
      the code violated the new one. The prohibition was false for four days with nothing red. A guard
      would have to assert what a tier's producer does to App-Group state; that is `architecture-guards`'
      argument to make, not this one (D3).

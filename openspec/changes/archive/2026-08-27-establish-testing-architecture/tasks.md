## 1. The new spec

- [x] 1.1 Create `openspec/specs/testing-architecture/spec.md` from this change's delta
      (`specs/testing-architecture/spec.md`): the Purpose plus nine ADDED requirements.
- [x] 1.2 Re-verify each factual claim in the spec against the checkout before landing it — the
      spec's whole value is that it describes today. Specifically: no `:app:*` module declares a
      test source set; `:ui:components/jvmTest`, `:test:architecture/src/test` and
      `:tools:diagrams/src/test` are the only genuine target-forgoing sets; `:adapter:ios:*`'s
      `iosTest` sets run on `iosSimulatorArm64`; `:test:world`'s `commonTest` holds feature tests;
      `:adapter:generic:fake`'s `commonTest` holds the fake-driven feature tests.
- [x] 1.3 Confirm the spec contradicts no gated law — in particular `module-architecture`'s
      "a translation hoisted inward to reach the faster test loop is rejected" scenario, which
      requirement 2's second paragraph exists to honour.

## 2. Corrections to existing specs

- [x] 2.1 Apply the `event-album` delta: `AlbumManager` is an `:adapter:ios:ext-safe` adapter
      covered by that module's `iosTest` suite, not an untested `iosMain` shell seam.
- [x] 2.2 Apply the `ios-photokit-upload` delta: Purpose points at `:domain`'s `feature/upload`
      rather than the retired `:capability:upload`; the orphaned-rows requirement names a `:domain`
      helper rather than a `domain`/`capability` one; the extension-root requirement says
      **untested** rather than **untestable**; the ordinal citation becomes a by-name one.
- [x] 2.3 Edit `upload-lifecycle`'s `## Purpose` directly (no delta file — the correction is in a
      Purpose, no requirement of that capability changes, and a Purpose-only delta is rejected by
      `openspec validate` with no precedent in the archive): the iOS composition root is a **file in
      `:app:ios`**, not "a module", and the rule gains a `testing-architecture` citation.
- [x] 2.4 Apply the `harness-world-model` delta: integration tests assert world outcomes, and
      `UiState` where the seam reaches presentation; the two ordinal citations become by-name ones.
- [x] 2.5 Apply the `ios-ci` delta: the `ios-test` job runs every source set the simulator target
      compiles, not only `commonTest`.
- [x] 2.6 Re-run the delta-fidelity check before landing: for each MODIFIED block, diff it against
      the current main spec and confirm the removed lines are only the intended ones. The generator
      script and its verification pass are the record of how these were built.
- [x] 2.7 Decide whether `harness-world-model`'s requirement **name** ("Integration tests assert
      UiState and world outcomes") should be renamed now that its body says otherwise. Renaming
      costs a RENAMED delta and churns nothing else; keeping it treats the name as a topic label.
      Either is defensible — record which and why.

## 3. CLAUDE.md and config

- [x] 3.1 Replace CLAUDE.md's `## Testing strategy` section with a single pointer line at
      `openspec/specs/testing-architecture/spec.md`. The elaboration under each rule is where four
      of the six audited falsehoods lived; a pointer has none.
- [x] 3.2 Confirm nothing parses the removed section: `LawsDigestTest` reads
      `## The laws (digest)` and `RunbookSkillsTest` reads the runbook pointer block. Neither
      touches `## Testing strategy`.
- [x] 3.3 Add a capability citation to `openspec/config.yaml`'s two testing sentences. They are
      true and terse; they stay.

## 4. Verification

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — structural only.
      Green means well-formed, not true; the truth pass is 1.2 and 2.6.
- [x] 4.2 `./gradlew build` — expected to be unaffected. This change touches no code, no build
      file, no test, and no CI. A red build means something unintended was edited.
- [x] 4.3 Confirm the four by-ordinal citations are gone:
      `grep -rn 'testing rule\|testing-rule' openspec/specs/` returns only the new spec's own
      Purpose, which *quotes* the ordinal as the problem it describes (the same legitimate-quote
      exception `openspec-archive-command` carries for the placeholder string).
- [x] 4.4 Confirm the remaining "wiring-only and untested by project rule" citations are untouched
      and still true — they are Bucket C and deliberately out of scope.

## 5. Follow-ups to file, not to do here

- [x] 5.1 File the `module-architecture` gap: `:app:ios:forge` and `:tools:diagrams` are in
      `settings.gradle.kts` and in `ModuleSetTest`'s target list but absent from the module-set
      requirement's enumeration and from CLAUDE.md's module list.
- [x] 5.2 File the `ios-ci` workflow-comment gap: `ios.yml`'s `ios-test` step comment names
      pre-migration modules (`:domain:*`, `:capability:*`, `:domain:keychain`, `:domain:ui`) and
      places `PhotoKitSmokeTest` in `:app:ios:extension`, where it no longer lives.
- [x] 5.3 File the open question on overlapping driver coverage:
      `:adapter:generic:app/iosSimulatorArm64Test` holds `NativeLedgerStoreTest` while
      `:adapter:ios:ext-safe/iosTest` holds both `IosLedgerStoreTest` and `IosDownloadStoreTest`.
      Deliberate or drift is unknown.
- [x] 5.4 Record, for the change that reverses the shells-untested rule, the 21 Bucket C citation
      sites this change deliberately left alone, and that
      `architecture-guards:188`/`:343`/`:1540` are its documented evidence base.

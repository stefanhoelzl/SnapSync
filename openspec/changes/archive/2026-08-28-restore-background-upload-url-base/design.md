## Context

`BackgroundUploadURLBase` has **two readers**, and only one of them is ours:

```
deployments/prod.json : domain
          │
   resolve-deployment.py
          │
   ┌──────┴───────────────────────────┐
   ▼                                  ▼
Deployment.plist                 Info.plist
(generated resource,             (hand-authored bundle manifest)
 copied into .app + .appex)             │
   │                                    ▼
   ▼                          assetsd reads BackgroundUploadURLBase
bakedUploadBase() →                and validates the registration
EdgeUploadRequestProvider host      INSERT against it
   ✅ still works                   ❌ deleted by 1d5d7a85
```

The app reads a **base URL**; `assetsd` reads a **destination constraint**, from a key Apple's template
names, in a file Apple's daemon opens. `1d5d7a85` classified the string as "a value the app READS" and
moved it whole, satisfying the first reader and orphaning the second. The generalisable statement is not
"a URL was in the wrong grammar" — it is **a value the OS reads was moved to a carrier only we read**.

The constraint that makes the restore non-trivial is `render_xcconfig`'s standing invariant: `//` opens a
comment anywhere on an xcconfig line, there is no escape, and the per-site `$()` guard the upload base
used to carry was deliberately deleted rather than generalised — *"a per-site escape is what failed here,
since it covers what someone remembered."* An `Info.plist` substitution can only read a build setting.
That is the bind this design resolves.

Grounding for the decisions below: every PhotoKit job destination is
`"$base/files/devices/$deviceId/$filename"` (`EdgeUploadRequestProvider`), where `$base` is the baked
`uploadBase`. There are no presigned URLs and no foreign origin, so every candidate value is a valid
prefix of every destination we create.

## Goals / Non-Goals

**Goals:**

- Registration succeeds again on iOS ≥26.1 under a full grant, restoring the OS-driven upload tier.
- The restored key cannot be silently deleted, truncated, or left unresolved without a **required merge
  gate going red on the same push**.
- Every document that asserted the deleted state is corrected, so the next reader is not talked into the
  same move.

**Non-Goals:**

- Making registration failure *survivable*. `resolveUploadMechanism` still cannot see a failed
  registration, so a future failure still means "uploads nothing on either tier". That is the durable fix
  and it is a separate change (see the proposal).
- Re-measuring the simulator's `-1`, or correcting the `3202` reading. Both are known-wrong claims and
  both belong with the change above.
- Trimming the app bundle's copy of the key.
- Any new architecture guard, renderer assertion, or value-shape validator.

## Decisions

### D1 — Compose the value in `Info.plist` from build settings, rather than generating or copying it

Three carriers were considered.

| | mechanism | verdict |
|---|---|---|
| **A** | `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1` in both `Info.plist`s | **chosen** |
| **B** | a build phase copies `uploadBase` from the built `Deployment.plist` into the built `Info.plist` | rejected |
| **C** | the resolver generates the extension `Info.plist` outright | rejected |

**B** appears strictly better — one string, copied, so drift is unrepresentable rather than checked. It
is rejected because it *destroys the check that replaces it*: a readback comparing `Info.plist` to
`Deployment.plist` would then compare a value to its own source, always equal, catching nothing. It also
buys a signing-order hazard (the phase must run before the signature) for a guarantee we can otherwise
obtain by assertion.

**C** carries `1d5d7a85`'s own principle, correctly applied: *"the fix was not a better escape; it was
moving the value into a grammar that escapes."* Generating the extension `Info.plist` removes the
xcconfig from the path entirely, so nothing can truncate — a stronger property than checking. It is
rejected on ownership: **one key of eleven in that file is deployment-derived**. The other ten are
platform facts, including `EXExtensionPointIdentifier = com.apple.photos.background-upload`, which was
verified on a device and has nothing to do with which backend we point at. A rendering is a projection of
the inventory; a bundle manifest is not one. C would make `resolve-deployment.py` the owner of ten Apple
facts to carry one of ours, and send the next person who changes an extension point into Python.

A's residual hazard — the truncatable xcconfig hop — is accepted **because D4 catches it**.

A also needs no inventory change: `UPLOAD_SCHEME` and `UPLOAD_HOST` are *derived* emissions of the
existing `domain` key, exactly as `ASSOCIATED_DOMAIN = applinks:{domain}` and `APS_ENVIRONMENT` already
are, which the `deployment-configuration` requirement "Renderers may derive; composition may not" already
permits.

### D2 — Two settings, not one with a hardcoded scheme

The tempting simplification is to hardcode `https` in the plist and emit only `UPLOAD_HOST`: the scheme
differs only for loopback, loopback is simulator-only, and the simulator substitutes the registry so the
key is never read there. Rejected, for a reason that is not the ATS rule:

```
one setting:  Info.plist = https://…        vs   Deployment.plist = http://127.0.0.1:8080/…
              → the two carriers disagree on the local deployment
              → "the carriers agree" becomes true only for prod
              → an invariant conditional on which deployment you resolved is not an invariant
two settings: they agree for every deployment, always.
```

`upload_scheme()` already exists and already encodes the rule, and its own docstring gives the same
reason: *"derived rather than declared so the two cannot disagree."* Deriving is free here.

### D3 — Bake the measured `/api/v1`, not the host alone

Host-only is tempting: the deleted comment said the system only permits destinations under this *host*,
and it would keep the API version out of the `Info.plist`. It is **unmeasured**, and this key's only
failure mode is silence.

The measured cost of the coupling is small and bounded: at a v2 flip the value's *shape* is unchanged
(`scheme://host/path`), so a daemon that accepts `/api/v1` accepts `/api/v2`; the only hazard is editing
one carrier and not the other, which D4 fails the PR for. **A coupling CI enforces is one line of
maintenance, not drift.** Against that, host-only is a blind edit to the thing being fixed, on a live
regression, whose failure would be learned from Bugsink three days later.

The duplication objection — `/api/v1` currently has exactly one home, `render_plist`'s f-string — is
answered by the same check rather than by machinery: a second home whose disagreement cannot merge is not
a drift source. A shared `API_PREFIX` constant plus a third derived setting would make it unrepresentable
and remains available if the literal ever bothers us; it is not worth the machinery today.

**Trigger that makes the measurement required rather than optional:** if any upload destination ever moves
outside `/api/v1` — a second API version served in parallel, a byte route off the versioned prefix — the
baked value stops being a valid prefix and the A/B must be run.

### D4 — Extend the existing archive readback; add no new guard

`ios-build` archives on **every push to every branch** (`on: push: branches: ["**"]`, no path filter) and
is a required merge gate. Its "Verify the archive baked the resolved deployment" step already loops over
the `.app` and the nested `.appex` reading four values from each, for exactly this reason: *"a renderer
test proves the intended bytes were emitted, and cannot see a grammar that reinterprets them … nor a
resource that never reached a bundle."*

One comparison is added per bundle: `Info.plist`'s `BackgroundUploadURLBase` must be non-empty and
**exactly equal** to that bundle's `Deployment.plist` `uploadBase`.

Exactness matters. The natural prefix formulation passes on both failures we most care about:

```
INFOBASE=""                                  → every string starts with it.            PASSES ✗
domain mis-authored as "https://snapsync.stho.net":
  uploadBase = https://https://snapsync.stho.net/api/v1   (Python, no truncation)
  INFOBASE   = https://https:                             (xcconfig truncated at //)
  prefix test                                                                          PASSES ✗
  equality test                                                                        FAILS  ✔
```

Because the two carriers compose the same fact by different routes — one through a grammar that escapes,
one through a grammar that comments — equality between them **is** a differential test of the grammar.
That is why no `//` assertion in `render_xcconfig` and no `domain` shape validator are added: they would
guard a hazard this comparison already catches, in the same push, from the built product. The renderer
half already exists in any case — `resolve_deployment_test.py`'s
`test_no_xcconfig_value_contains_a_comment_delimiter` asserts it over every emitted value, and its
`xcconfig_values()` helper reads a rendering *the way xcodebuild does*, truncating at `//`. The two new
settings inherit that coverage on the day they are emitted; what they need is a read-back assertion of
their own values through that same helper.

A source-level `:test:architecture` pin was also considered and rejected as redundant: it would assert the
key's *presence in source* on the same pushes where the readback asserts *the built bundle carries the
right value*. Strictly weaker, same schedule. (Its precedent is real — `RuntimeIdentityTest` pins the
BGTask ids in both Kotlin and `Info.plist` for this identical daemon-shaped silence — but that pin exists
because nothing reads those ids back out of a built bundle. Here something does.)

### D5 — Restore to both bundles

The A/B covered the appex. If the "present" arm carried the key in both bundles, then we know the pair
works and **we do not know which copy `assetsd` read** — and there is a plausible mechanism for the app's
copy mattering, since the registration call is made by the app process and the daemon validating that
insert could reasonably read the calling bundle. Restoring both makes the question moot instead of
betting on its answer. Trimming later is a measurement, not a cleanup.

### D6 — Claim only what was measured

The spec records two measurements — key absent → `-1` with empty `userInfo`; key present as
`https://<domain>/api/v1` → success and read-back `true` — and states that **the matching rule is not
established**. It must not restate the deleted comment's *"the system only uploads to destinations under
this host"*: that is the same unverified-inference class as `3202` = "a stale record", which has
misdirected two investigations. Expiry trigger, per the forcing-proof law and alongside the other
PhotoKit facts: **re-measure at the next iOS major**.

## Risks / Trade-offs

- **The `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1` substitution might not resolve in `Info.plist`
  preprocessing** (multiple substitutions in one value, literal `://` between them) → the readback in D4
  fails the branch push on an unresolved substitution, because the literal `$(UPLOAD_HOST)` text would not
  equal `uploadBase`. Verification costs nothing extra: pushing the branch archives on macOS and runs the
  check. If it does not resolve, the fallback is `INFOPLIST_PREPROCESS`-free composition — a single
  `UPLOAD_HOST` with `https` and `/api/v1` literal in the plist, accepting D2's conditional invariant.
- **CI cannot prove `assetsd` accepts the string**, only that the bundles carry it → the value restored
  is the one measured to work, unchanged. A wrong *shape* would still ship silently; that is the residual
  risk D3's trigger and D6's expiry bound.
- **The fix does not make the tier fallback exist** → any future registration failure still means no
  uploads on either tier with one `Error` line. Named in the proposal as the follow-up change; this one
  restores the tier rather than hardening it.
- **Both `Info.plist` comments and the `Config.xcconfig` header now say something subtler** ("this value
  is here because the OS reads *this file*") than the blanket rule they replace ("no deployment values
  here") → a subtler rule is easier to misread than an absolute one. Mitigated by naming the daemon, the
  error code, and the date of the measurement at each site, rather than restating the principle.

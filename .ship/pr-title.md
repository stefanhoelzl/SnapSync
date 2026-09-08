# PR title policy

Applies to a `feat: ` / `fix: ` title — i.e. whenever `/ship` has settled on the `feature` or
`bugfix` category. It constrains what the title may **say**; the mechanics (the prefix, the
parenthesised-title path, three options via AskUserQuestion, your answer being final) belong to
`/ship` itself and are not restated here.

⚠️ **This title is the App Store bullet.** It is not addressed to this repository. At release
time `.github/scripts/release_notes.py` takes the title of every `enhancement`/`bug` PR in the
range, applies exactly three transforms — strips `type(scope):`, strips a leading
`Fix`/`Fixes`/`Fixed`, capitalizes the first letter — and publishes the remainder **verbatim** as
a `- ` bullet under **New** or **Fixed** in the App Store listing. There is no editorial pass
after this moment, and correcting an already-promoted version's notes is a manual console upload.
Write the sentence a customer reads.

This is what shipped to customers when that was forgotten:

| PR | what the App Store showed |
| --- | --- |
| #202 | *"Take the photo-library import out from under the lock."* |
| #200 | *"Hold both background-session completion handlers in a bounded receipt."* |
| #151 | *"Download page."* |
| #133 | *"UI refresh."* |

**The four rules.**

1. **Every noun must be one a SnapSync user has seen** — in the app or the App Store listing:
   *event, photos, album, join, leave, share, sync, invite, QR code, phone, event settings, event
   dates, download*. If a word names something only this repository knows about, it may not
   appear. Observed leaks, as examples rather than as the list: *ledger, manifest, upload cycle,
   PhotoKit, URLSession, App Group, port, flow, lock, completion handler, MIME, UTI, extension,
   backend, endpoint, cursor, seam, adapter*.
2. **Name an observable outcome, never the area touched.** Vagueness and jargon are the same
   failure wearing two faces — both describe the change to the repository instead of the change to
   the user. *"Download page"* → *"Download an event's photos from the web"*. *"UI refresh"* →
   name what a user now sees or can do.
3. **A `bug` title states the symptom, gone.** It appears under a heading that already says
   **Fixed**, so *"Photos no longer arrive twice in an event"* reads correctly and *"Corrected the
   dedup key"* does not. Good shape: #196 *"photos no longer arrive twice for everyone in an
   event"*, #187 *"garbled screen after the app has been in the background"*, #217 *"config
   buttons appeared delayed"*.
4. **No scope.** The prefix is bare `feat: ` or `fix: ` — never `fix(download): `. The renderer
   strips a scope either way, so this costs the customer nothing; it exists to keep you writing to
   the customer rather than to the module. #202 and #182 broke this rule.

**The rendering.**

Each option MUST carry, in its `preview` field, the **rendered customer-visible line** — the
three transforms applied, under the heading it will appear beneath:

```
Fixed
- Config buttons appeared delayed
```

Approve what the customer reads, not what the repository reads.

**Where the detail goes.** The release notes read the **title** only, never the body. So a
user-facing title that lost detail has not lost it from the PR: the mechanism, the module, the root
cause and the internal vocabulary rule 1 forbids all belong in the PR body — put them there rather
than smuggling them back into the title.

**If there is no user-visible symptom at all**, this is not a `feat`/`fix` title problem: the PR is
`internal`, so go back to the category step.

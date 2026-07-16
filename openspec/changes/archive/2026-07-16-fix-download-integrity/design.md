## Context

The download path has one integrity signal and never reads it. `IosDownloadTransport.onFinished` stages
whatever URLSession handed it; `downloadTask.response` is available on the same object and untouched.

```
  NOW                                          bytes are truth the moment they land
  ───                                          ────────────────────────────────────
  URLSession ──didFinishDownloadingToURL──► onFinished
    (fires for 502 too, error = nil)             │
                                                 ├─ destinationFor(description)
                                                 ├─ moveToStaging(temp → staging)
                                                 └─ host.onStaged ──► store.markStaged
                                                                          │
                                                       importableAssets() ─┴─► import
                                                                                │
                                                                        PHAsset rejects XML
                                                                                │
                                                                    "retried later" ──┐
                                                                                ▲     │
                                                                                └─────┘
                                                                     forever. no re-download.
```

The store is the reason this is permanent rather than merely wrong. `markStaged` is a claim that the bytes
arrived; nothing revisits it. So an invalid body is not a failed download that retries — it is a
*successful* download of garbage that the importer will reject on every reconcile until the event is left.

## Goals / Non-Goals

**Goals**
- Make the question askable: the outcome facts reach code that can act on them.
- Decide in `commonTest`-covered `commonMain`, per `IosDownloadTransport`'s own stated contract.
- A rejected transfer re-downloads rather than poisoning the store.

**Non-Goals**
- **Content correctness.** A `200` serving the wrong photo's bytes passes every check here. That needs a
  hash the edge URL layout does not carry.
- **Upload integrity.** The upload edge is a `PUT` with its own idempotency; this is the download half only.
- **Reworking retry-on-failed-import.** That behavior is correct once its assumption holds; this change
  makes the assumption hold rather than replacing it.

## Decisions

### D1. The predicate lives in `QueuedPhotoDownloadJobs`, not in the ObjC edge

`IosDownloadTransport`'s KDoc already binds this: the edge is *"the ObjC edge and nothing more"*, and the
queue, window, description codec, staging-path derivation and URL guard all live in
`QueuedPhotoDownloadJobs` *"where they are covered by `commonTest`"*. An integrity rule implemented in
`iosMain` would be the one rule in this capability with no test on either target — and CLAUDE.md's testing
rule is that logic lives where `commonTest` reaches it.

The edge therefore does exactly two new things, both irreducibly Obj-C: read `statusCode` /
`expectedContentLength` off `task.response`, and stat the temp file. Everything judgemental crosses the
seam as data.

### D2. Reject only when rejecting can eventually yield the photo

`photo-selection-policy` **admits on doubt**, and says why: *"a stray uploaded meme is harmless and visible,
while an event photo that silently fails to upload is invisible and unfixable."* The instinct is that
download integrity simply inverts it — admitting bad bytes is the permanent, invisible failure here, so
reject on doubt:

| | admit bad bytes | reject good bytes |
|---|---|---|
| **cost** | permanent poison pill; photo never arrives; retried forever | one re-download |
| **visibility** | invisible — logs say "import deferred" | invisible — it just works |

That table is right and "reject on doubt" is still wrong, because the right-hand column assumes the retry
can succeed. It cannot always. `expectedContentLength` is `-1` whenever the server omits `Content-Length`
(D3), and it will omit it again on every retry — so rejecting there is not "one re-download", it is an
unbounded loop that never yields the photo. That is the *same* permanent invisible loss as admitting bad
bytes, reached from the other side.

So the rule both cases obey is narrower than either instinct:

> **Reject a transfer only when rejecting can eventually yield the photo** — i.e. on positive evidence of
> badness (a non-2xx status; a known length the body falls short of), never on the mere absence of
> evidence (no status, no `Content-Length`).

Doubt that a retry can resolve is worth rejecting on: a truncated body is usually a one-off, and the next
attempt succeeds. Doubt that is *structural* — the server simply does not send that header — is not doubt
about these bytes at all, and rejecting on it converts a working download into a permanent one. This is the
principle; D3 is its consequence, not an exception to it.

### D3. Unknown length is not short — D2 applied

`expectedContentLength` is `NSURLResponseUnknownLength` (`-1`) whenever the server omits `Content-Length`,
which chunked responses routinely do. By D2 that is absence of evidence, not evidence of truncation, and a
retry cannot resolve it — so it is accepted, and the status check stands alone.

The rule is: reject when the length is **known and the received count is below it**. Over-length is
accepted too — it cannot be a truncation, and rejecting on a server quirk that recurs every attempt is
another D2 infinite loop.

An unknown **status** is the same shape and gets the same answer. `isFetchableUrl` already restricts
transfers to `http`/`https`, so a response with no HTTP status should be unreachable; if one arrives
anyway, it is unexplained rather than bad, no retry resolves it, and D2 says accept — with a log, because
it should never happen.

This is the one place where getting the fix wrong is worse than the defect, so the tests pin every case:
known-and-short (reject), known-and-exact (accept), unknown length (accept), over-length (accept),
non-2xx (reject), unknown status (accept).

### D4. A rejection must not reach `markStaged`

The whole defect is that `markStaged` is believed. A rejected transfer therefore reports only through the
terminal path (`onCompleted` with an error), which frees the window slot and leaves the resource
un-staged — so the next reconcile re-downloads it rather than re-importing garbage.

The staged file must also not be left behind under the destination path: `moveToStaging` does
`removeItemAtPath` then `moveItemAtURL`, so staging a rejected body would *destroy a previously-good file*
at the same path on a re-download. The check therefore runs **before** the move, not after — which is also
free, since URLSession deletes the temp file when the delegate returns and the move is the only thing that
would have to be undone.

### D6. The world fakes the transport, not the jobs — and takes the caller's scope

This was found while implementing, not while planning. The world fakes `PhotoDownloadJobs`
(`FakePhotoDownloadJobs`), which is the layer **above** `QueuedPhotoDownloadJobs`; `git grep` finds zero
references to `QueuedPhotoDownloadJobs` or `DownloadTransport` anywhere in `test/world` or
`test/integration`. So the world replaces the entire download orchestration — the bounded window, the
description codec, the URL guard, and now the integrity check — and exercises none of it. Adding an outcome
to the existing fake would have forged the answer rather than tested the code.

That contradicts the capability's own premise: `harness-world-model` exists so *"the REAL platform-agnostic
stack"* runs against a controllable world, and its rationale is that *"faking the **execution edge** rather
than the logic lets the real stack run anywhere."* `DownloadTransport` is the execution edge.
`PhotoDownloadJobs` is the logic. The world faked the wrong one.

**The scope is unavoidable, and must be the caller's.** `QueuedPhotoDownloadJobs` takes a `CoroutineScope`
at construction and uses it in three host callbacks to hop off the ObjC delegate thread. `World` takes none.
It must not own one: a world-owned scope would outlive the caller, leak staging work between tests, and be
unjoinable by the operator. The driver's scope is the right one — inside `worldTest` that is the
`runBlocking` scope, and in the desktop harness the inspector's, which it already has. So `World(scope = …)`,
and 49 call sites gain an argument (all the identical shape `= World()`).

*An earlier draft of this decision justified the scope by `runTest`'s scheduler and `advanceUntilIdle`.
That was wrong and worth recording: `worldTest` is `expect fun worldTest(body: suspend CoroutineScope.() ->
Unit)`, actualized as **`runBlocking`** on both targets. `:test:world` has no test scheduler and never calls
`advanceUntilIdle`. The correct reason is ownership and lifetime, not virtual time.*

**Routing through the real jobs introduces a race the old fake could not have.** `stageAllDownloads()` used
to call the suspend `downloadController.onResourceStaged` directly and await it. The real
`QueuedPhotoDownloadJobs.onStaged` is **not** a suspend seam — in production it is called from the ObjC
delegate thread, so it must `launch` — which means the operator action would return before anything was
imported, and every download assertion in the world would become a race. Under `runBlocking` there is no
scheduler to advance, so the world keeps the launched `Job`s and `joinAll`s them in `stageAllDownloads`. An
operator action is complete when it returns; that property is what the harness sells.

**Two alternatives rejected.** An *optional* scope falling back to the old fake would give the world two
download paths — the same "second way to drive that can rot or lie" this project rejects for the harness
driver, and the rotting one would be the default. A *per-call* factory (`downloadJobs(scope)`) breaks
`World.kt:133`: *"Single-instance real download controller (its Mutex must be shared across reconcile +
staging)."*

**Why this is welded to the fix rather than split out.** It is defensible either way, and the cost is real:
this change now spans two capabilities and four modules for a bug fix. It rides along because the
alternative is shipping an integrity check whose only coverage is a unit test of its own predicate, while
the harness that exists to prove download behavior end-to-end structurally cannot see it — and the harness
gap is not hypothetical, it is what made task 4 unimplementable as written. Splitting would land the fix
sooner and leave the harness lying for one more change.

### D5. Establish the real failure mode before fixing it, and do not fake a test

Two failure modes are argued in the proposal and only one is certain. Non-2xx staging an error body follows
from documented URLSession behavior. A true short read reaching `didFinishDownloadingToURL` — rather than
surfacing as `didCompleteWithError` — is plausible but unverified here.

That distinction changes nothing about the fix (both are rejected by the same predicate) but it changes
what the change may *claim*. `fix-upload-config-gate` set the precedent: *"The original plan opened with a
failing test. That is not possible here, and the reason is the finding — do not fake one."* The same holds:
today the seam cannot express a bad outcome, so the test that would fail cannot be written until the seam
widens. Task 1 records what can be demonstrated instead.

**Outcome: half settled, and the unsettled half is unsettleable here.** The poison pill is *proven* — not
inferred — by `DownloadStore.sq`: `selectPendingResources` excludes any row with a non-null `stagedPath`, so
staged garbage is never re-fetched, while `selectImportableAssets` keeps an all-staged asset importable
until it imports. Staging a bad body is therefore terminal by construction. Only its *trigger* (that
`PHAssetCreationRequest` rejects an error body) stays inferred, and both branches justify the fix — reject
loops forever, accept lands a non-photo in the library.

What URLSession actually delivers cannot be settled by any test in this repo, and that is structural rather
than a gap to fill later: every test fakes the transport, so a test asserting "a `502` reaches
`didFinishDownloadingToURL`" asserts what the fake was told and passes whatever the truth is — evidence-
shaped, evidence-free. It is worse than the absence of a test. Only a device can answer it, and the device
route is closed twice over (task 1.4: no hook can aim the app at a failing URL, and no foreign content
exists to download). The non-2xx case is documented Apple behavior and certainly occurs; the short-read case
stays open, costs nothing under D3, and would be answered by the `transfer finished` log line the moment a
foreign photo exists.

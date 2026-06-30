# Design — device-facing custom domain (`snapsync.stho.net`)

## Context

Today the device-facing origin is baked at compile time into the IPA. `Config.xcconfig` sets
`BACKGROUND_UPLOAD_URL_BASE = https://snapsync.stefanhoelzl.deno.net`; that flows into both
`Info.plist`s as `BackgroundUploadURLBase`, and `SnapSyncRoot.kt` reads it for **three** uses — the
upload host, the `HttpEventCreationClient` host (`POST <host>/event`), and the invite/deeplink URL.
The upload extension reads the same key via `uploadHostFromBundle()`. So **one literal** is the whole
device-facing front door.

The backend (`backend/main.ts`, one source) deploys to **both** bunny Edge Scripting and Deno Deploy.
Deno Deploy is the **active** device-facing runtime: bunny.net was dropping iOS's zero-window upload
SYNs, so traffic was moved to Deno Deploy (workflow comment: "when bunny fixes the SYN drop, drop
this step + repoint the iOS host"). The `backend-deployment` spec predates the Deno Deploy addition
and still says "the one live Edge Script" — drifted.

The problem: the baked host is a name **Deno owns** (`*.deno.net`). Any runtime swap forces a new
compile → new TestFlight/sideload → reinstall. Bunny only ever sees server-side storage traffic
(`storage.bunnycdn.com`, zone `snap-sync`); there is no bunny CDN/pull-zone front door. ATS is
exception-free, so the origin must serve a publicly-trusted cert.

## Goals / non-goals

- **Goal:** make the device-facing origin a domain **we** control (`snapsync.stho.net`, a Bunny DNS
  zone), so the baked host literal never has to change again.
- **Goal:** reconcile the `backend-deployment` spec to the dual-runtime reality.
- **Non-goal:** move iOS traffic back to bunny. The SYN-drop is unresolved; Deno Deploy stays the
  active runtime. The custom domain merely *prepares* a future runtime swap as a DNS repoint.
- **Non-goal:** keep old (deno.net-baked) installs working. Hard cutover — single user, one device.
- **Non-goal:** any change to storage config, the engine, the ledger, or the UI.

## The decoupling

```
        BEFORE                                    AFTER
   ┌───────────────┐                        ┌───────────────┐
   │  iOS app/ext  │  baked literal =        │  iOS app/ext  │  baked literal =
   │  (IPA)        │  PROVIDER vanity host   │  (IPA)        │  OUR domain
   └──────┬────────┘                        └──────┬────────┘
          ▼                                        ▼
   snapsync.stefanhoelzl.deno.net          snapsync.stho.net   ◄── Bunny DNS (we own)
          │ (Deno owns this name)                  │  CNAME → active runtime
          ▼                                        ▼
   ┌─────────────┐                          ┌──────────────────────┐
   │ Deno Deploy │                          │ active runtime        │
   └─────────────┘                          │ Deno today (custom    │
                                            │ domain + Let's Encrypt)│
   swap runtime ⇒ NEW IPA ⇒ TestFlight       │ bunny later = DNS swap │
                                            └──────────────────────┘
```

The invariant bought: the **single baked host literal** and `PUBLIC_BASE_URL` both name a domain we
control. Swapping the runtime that answers it is a DNS repoint + a server-side `PUBLIC_BASE_URL`
flip — never a new build.

## DNS + TLS shape

`snapsync.stho.net` is a **subdomain**, so Deno Deploy's **CNAME method** applies (no apex
flattening): one `CNAME` `snapsync` → the Deno-provided target, plus the `_acme-challenge` `CNAME`
for ACME verification. Deno then provisions a Let's Encrypt cert (~90 s) and auto-renews. Both
records live in the `stho.net` Bunny DNS zone; we add them via the Bunny **account API key**
(`BUNNY_API_KEY` through `proton-env`, which prompts for sign-off per run). The Deno "Add Domain" +
cert-provision + assign-to-app steps are dashboard-only (that's where the record targets originate).

## Cutover ordering (hard cutover — order matters)

```
1. Deno dashboard: Add Domain snapsync.stho.net (CNAME method, no wildcard)
        └─ copy: snapsync CNAME target + _acme-challenge value
2. Bunny DNS API: add both CNAMEs to the stho.net zone        ◄── we drive this
3. Deno dashboard: verify → Provision Certificate (~90s) → assign to snapsync app
4. Verify: snapsync.stho.net serves the backend over HTTPS    ◄── GATE before flipping literals
        (e.g. GET /event/<known-id> returns the marker JSON over TLS)
─────────────────────────────────────────────────────────────
5. Flip PUBLIC_BASE_URL → https://snapsync.stho.net  (workflow; redeploys Deno env + list urls)
6. Flip BACKGROUND_UPLOAD_URL_BASE → https://snapsync.stho.net  (xcconfig)
7. New iOS build from main → reinstall on device → confirm a real upload lands in the bunny zone
```

Steps 1–4 (domain live + cert) MUST complete before the literal flips (5–6) merge to `main`: a hard
cutover means the new build can only reach the backend if `snapsync.stho.net` already serves it.

## Decisions

- **Fold into `backend-deployment`, no new capability.** The domain story (own front door, CNAME to
  active runtime, TLS, DNS-repoint-not-rebuild) lives in `backend-deployment` rather than a separate
  `device-facing-domain` capability — smaller surface; the deploy spec is the natural home for "where
  the backend answers from."
- **No `ios-background-upload` / `backend-config` delta.** Both already require an HTTPS,
  publicly-trusted origin / "the public origin" semantically; neither hardcodes the host literal, so
  the value swap is config, not a contract change.
- **Hard cutover, not parallel.** Single user, one device. Parallel-run (keeping deno.net live for old
  installs) buys nothing here and complicates the mental model. The `.deno.net` vanity URL keeps
  resolving regardless (Deno auto-assigns it; it cannot be deleted) — we simply stop referencing it.

## Risks / open threads

- **Streaming PUT parity (verify on device).** Deno's custom-domain edge must handle the iOS
  background-upload streaming PUTs identically to the `.deno.net` URL. Same app, different SNI —
  expected fine, and the SYN-drop that sidelined bunny was bunny-specific. Confirmed only by step 7
  (a real upload landing in the bunny storage zone); the `dvt screenshot` status counts are
  informational, not authoritative.
- **ACME via Bunny DNS.** Verification needs the `_acme-challenge` `CNAME` resolvable and
  un-proxied. Bunny DNS is authoritative for the records (not a proxying CDN layer), so no
  Cloudflare-style "disable proxy" caveat applies — but cert provisioning waits on DNS propagation.

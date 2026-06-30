## 1. Provision the custom domain (operator + Bunny DNS API) — must complete before §3 merges

- [x] 1.1 **Deno dashboard (operator):** Add Domain `snapsync.stho.net` on the `stefanhoelzl/snapsync`
  app, CNAME method, **no wildcard**. Record the `snapsync` `CNAME` target and the `_acme-challenge`
  verification value from the configuration drawer.
  → routing target = `snapsync.stefanhoelzl.deno.net`; verification = `48101e8fc7ec49d216fe2aeb6329fb5d._acme.deno.net`.
- [x] 1.2 **Bunny DNS (driven via API):** add to the `stho.net` zone a `CNAME` `snapsync` → the Deno
  target and the `_acme-challenge` verification `CNAME`, using the Bunny **account API key**
  (`BUNNY_API_KEY` via `proton-env`; expect a sign-off prompt per call). Confirm both records resolve.
  → zone id `817890`; records `snapsync`→`snapsync.stefanhoelzl.deno.net` (Id 18888535) and
  `_acme-challenge.snapsync`→`…_acme.deno.net` (Id 18888272); both confirmed resolving via 1.1.1.1/8.8.8.8.
- [x] 1.3 **Deno dashboard (operator):** trigger verification, **Provision Certificate** (~90 s,
  Let's Encrypt), and **assign** the domain to the `snapsync` app.
  → verified + cert provisioned (CN `snapsync.stho.net`, Let's Encrypt, exp 2026-09-28). Routing
  CNAME corrected to Deno's canonical `alias.deno.net` (the drawer's value, not the vanity host).
- [x] 1.4 **Gate:** confirm `https://snapsync.stho.net` serves the backend over TLS with a
  publicly-trusted cert — e.g. `GET https://snapsync.stho.net/event/<known-event-id>` returns the
  marker JSON (or `404` for an unknown id), not a TLS/connection error. Do not proceed to §3 until green.
  → GREEN: chain `snapsync.stho.net`→`alias.deno.net`→`69.67.170.170`, valid cert, `GET /event/<unknown>`
  → `404 "event not found"`. (Sandbox system resolver negative-cached the old NXDOMAIN; verified via
  `dig @1.1.1.1` + `curl --resolve`.)

## 2. Reconcile the spec drift (`backend-deployment`)

- [x] 2.1 Update `openspec/specs/backend-deployment/spec.md` per this change's spec delta: dual-runtime
  deploy (bunny Edge Scripting + Deno Deploy, one bundle, main-only), Deno Deploy as the active
  device-facing runtime, the custom-domain origin, the baked-host == `PUBLIC_BASE_URL` invariant, and
  the runtime-swap-is-a-DNS-repoint requirement. (Synced at archive.)
- [x] 2.2 Update the spec **Purpose** prose that still says "the one live Edge Script" to reflect the
  dual-runtime + custom-domain reality. (Synced at archive.)

## 3. Flip the two host literals (one PR; merges only after §1 is green)

- [x] 3.1 `iosApp/Configuration/Config.xcconfig`: `BACKGROUND_UPLOAD_URL_BASE` →
  `https:/$()/snapsync.stho.net` (keep the `$()` `//`-comment guard). This is the single source for
  the upload, event-creation, and invite/deeplink origin. (Comment updated: custom domain, never a
  provider vanity host.)
- [x] 3.2 `.github/workflows/backend-deploy.yml`: the hardcoded `PUBLIC_BASE_URL` URL →
  `https://snapsync.stho.net` (the Deno Deploy env step + any other reference). Update the revert-path
  comment to frame a future bunny switch as a **DNS repoint + `PUBLIC_BASE_URL` flip**, not an iOS
  rebuild. (Header + revert comments updated.)
- [x] 3.3 Confirm no other live reference to `snapsync.stefanhoelzl.deno.net` remains in non-archive
  sources (grep); the `.deno.net` vanity URL stays auto-assigned but unreferenced. (grep clean.)

## 4. Docs

- [x] 4.1 `docs/design.md` (§4/§7): record the custom-domain front door, the provider-independence
  invariant (baked host == a domain we control == DNS-repoint-not-rebuild), and reframe the
  bunny-revert path accordingly. (§4 upload-endpoint bullet + `BackgroundUploadURLBase` bullet.)
- [x] 4.2 `backend/README.md` deploy section: dual runtime (bunny + Deno Deploy), Deno Deploy active,
  the `snapsync.stho.net` custom domain, and the DNS-repoint revert path. (Opening + Deploy section.)

## 5. Build & verify on device (closes the hard cutover)

- [ ] 5.1 Cut a new iOS build from `main` (carrying the flipped xcconfig host) and install it on the
  device, replacing the old (deno.net-baked) build.
- [ ] 5.2 Provision against a **fresh event id** and confirm a real upload **lands in the bunny storage
  zone** via `https://snapsync.stho.net` (the authoritative check — not the status-screen counts),
  proving Deno's custom-domain edge handles the iOS streaming PUTs identically to the `.deno.net` URL.
- [ ] 5.3 Confirm event creation (`POST /event`) and the invite/deeplink URL now carry
  `snapsync.stho.net`.

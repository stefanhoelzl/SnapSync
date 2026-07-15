## 1. Page source

- [x] 1.1 Move the reviewed draft `web/index.html` to `backend/src/landing.html` (canonical source of truth); remove the throwaway `web/` preview directory.
- [x] 1.2 Sanity-check the page is self-contained: no external origins for styles/scripts/fonts/images (icon inlined as data URI), no analytics/cookies, and it contains `id="privacy"`, `id="terms"`, the GitHub issues link, and a `mailto:` contact.

## 2. Serve at the root

- [x] 2.1 In `backend/src/app.ts`, import the page as text: `import LANDING_HTML from "./landing.html" with { type: "text" };`.
- [x] 2.2 Add a `GET /` handler returning `LANDING_HTML` with `Content-Type: text/html; charset=utf-8` and `Cache-Control: public, max-age=300` (do not use the listing routes' `no-cache`).
- [x] 2.3 Ensure `HEAD /` is answered with the same status/headers and no body.

## 3. Open the gate for exactly the root

- [x] 3.1 In the single `app.use("*")` attestation gate, extend the pass-through predicate to also admit `((method === "GET" || method === "HEAD") && path === "/")` — exact path, no prefix, no other method. Add a brief comment tying it to the `marketing-site` capability and the closed-list requirement.

## 4. Tests

- [x] 4.1 Add backend tests (Deno): `GET /` → `200`, `text/html; charset=utf-8`, cacheable `Cache-Control: public`, body includes the `#privacy` and `#terms` anchors and the support/contact links.
- [x] 4.2 Add a test that `GET /` and `HEAD /` succeed **without** an `Authorization` header.
- [x] 4.3 Add a regression test that the gate is not widened: an unauthenticated `GET` to a non-`/` path still returns `401`, and a non-`GET`/`HEAD` method on `/` still returns `401`.

## 5. Build & verify

- [x] 5.1 Run `deno task bundle` in `backend/` and confirm the HTML is inlined into `dist/main.js` (grep for a unique page string) and the bundle builds clean.
- [x] 5.2 Run `deno task check`, `deno task lint`, `deno task fmt`, and `deno task test` — all green.
- [x] 5.3 Locally serve/exercise the app (MockEngine or the bundled handler) to confirm `GET /` renders the page and the upload/attest routes still gate as before.

## 6. Spec validation

- [x] 6.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.

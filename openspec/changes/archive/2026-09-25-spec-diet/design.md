# Spec diet — design

## Context

Triage of all 62 specs (2026-09-25) found ~12–15% user-observable content, 17 engineering/process
specs with no app-user content at all (~7.9k lines), nine capabilities named after mechanisms
(sync-ledger, sync-engine, database, download-store, device-identity, device-state-reset,
edge-upload-provider, gallery-status, deployment-configuration), heavy cross-spec restatement, and five
contradictions (album default, the 30-day creation cap, the join range control, stale event-link
defaults, whether the switch's leave blocks).

Research on AI spec-driven development (OpenSpec `docs/concepts.md`, GitHub Spec Kit, Kiro, Böckeler's
SDD tool survey, Thoughtworks Radar, Nygard ADRs, Diátaxis) converges on the same split: spec = WHAT
and WHY, observable behavior and external constraints; plan/design = HOW; persistent project context
for cross-cutting rules; decision records for rationale; rules that must always hold belong in gates,
docs explain them. OpenSpec's own swap test: "if implementation can change without changing externally
visible behavior, it likely does not belong in the spec."

## Goals / Non-Goals

Goals: specs stop colliding with designs; one home per outcome; every user-observable outcome and
every privacy/security/retention promise survives; engineering knowledge has a durable home outside
the contract so it does not flow back.

Non-goals: stable scenario IDs / test↔scenario links (deferred until the 14 specs are stable); a
numeric size budget (the swap test is the budget); fixing the two behavior contradictions (separate
changes).

## Decisions

- **Audience.** App users plus invisible promises. Security is user-facing. Operator/dev contracts
  (release pipeline, CI, changelog labels) are not specs; they live in `docs/deployment.md` and the
  enforcing workflows/scripts.
- **Screens.** A spec says what a screen lets the user do and how it guides them — not layout,
  visuals, or exact copy.
- **External formats.** Only the invite link / QR (printed QRs must open forever). The HTTP API is
  bounded by the minimum-app-version mechanism; the device manifest is backend-derived and
  re-derivable; object naming may change (an update may re-upload).
- **Where cut content goes.** Code + KDoc (implementation); port-contract clauses (measured platform
  facts); archived decision records (rationale, unchanged); `docs/architecture.md`,
  `docs/testing.md`, `docs/deployment.md` (engineering, each covering app and api).
- **Merge map.**

  | New capability | From |
  |---|---|
  | create-event | event-creation-ui, event-limits (window) |
  | join-event | join-event, join-share-count, event-link, ios-app-shell (links) |
  | manage-membership | reconfigure-membership, event-rename, event-invite-qr, leave-event |
  | photo-sharing | photo-selection-policy, device-manifest, sync-ledger, upload-state-reconciliation, device-identity |
  | background-upload | upload-lifecycle, ios-photokit-upload, ios-url-session-upload, sync-engine, edge-upload-provider |
  | receiving-photos | photo-download, download-store, push-registration, apns-push-sender, upload-completion-notify |
  | event-album | event-album |
  | photo-access | permission-gate, limited-photo-access |
  | sync-status | sync-status-screen, sync-status, gallery-status, design-system (interaction), ios-app-shell (app-level) |
  | event-lifetime | event-limits (lifetime, capacity), scheduled-cleanup, database (capacity) |
  | app-update-required | min-app-version |
  | web-site | marketing-site, web-site |
  | event-site | web-event-download, event-link (no-app landing) |
  | privacy-security | crash-reporting, diagnostic-logging (bug report), device-attestation, web privacy rules |
  | docs/architecture.md | module-architecture, architecture-guards, port-contracts, complexity-budgets, coverage-bounds, architecture-diagrams, design-system (rules), api-endpoints, database |
  | docs/testing.md | testing-architecture, harness-world-model, full-stack-harness, desktop-test-harness, desktop-app-shell, device-state-reset |
  | docs/deployment.md | backend-deployment, deployment-configuration, ios-ci, ios-testflight-delivery, ios-appstore-release, ios-appstore-metadata, changelog-labels |
  | openspec/config.yaml | openspec-archive-command |

- **Process.** Not run as an OpenSpec change: 62 MODIFIED/REMOVED deltas would be large and fragile
  (a MODIFIED delta restates a whole requirement). The specs were replaced directly in one PR, with
  this record as the decision; docs were written before any spec was removed.
- **Re-bloat guard.** WHAT EARNS A SPEC in `openspec/config.yaml`; the archive gate forbidding code
  identifiers in specs replaces the delta-completeness gate (which tied every touched module to a
  spec — the mechanism that pulled implementation in) and the dead-types gate (moot once specs name no
  types).
- **Stale statements** where the old specs contradicted the code were resolved toward code truth and
  listed for the user's review in the PR.

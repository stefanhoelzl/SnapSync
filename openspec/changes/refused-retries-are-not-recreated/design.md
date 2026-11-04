# Design

## Context

See proposal.md — Why. iOS's upload queue (≥ 26.1) presents two things the upload extension must answer at every
invocation, before anything else (`SettlePresentedJobs`, phase 4.4b): the **retries it offers** — a first failure's one
free retry, which the extension re-points at a freshly built request — and the **terminal jobs** — recorded, then
acknowledged; a failed one whose bytes the queue still holds is re-created at once.

Neither path asks the membership's current choices. Today:
- `Presented.RUN` re-points every offered retry and re-creates every held failure "as the queue presents it, without the
  policy's word" (its KDoc).
- `Presented.DECLINED` — a membership that shares nothing — still re-creates the held failures.

The walk does not catch these rows: it withdraws only rows inside its window whose photo it no longer finds or admits, and
deliberately keeps a row outside the window (a raised cutoff never un-shares what already landed). So a photo whose upload
failed just before the member narrowed their range, or switched to receiving only, is sent again on the next pass. The
app's own uploader is not affected: a failed session transfer returns its row to needing a job, and only the top-up —
which plans by the current policy (`PlanTopUp`) — makes another.

The one-row judgement already exists: `SelectionPolicy.admitRecorded` over a row's `recorded` asset, as the top-up and the
manifest use it.

## Goals / Non-Goals

**Goals:**
- No offered retry is re-pointed and no failure is re-created for a photo the membership's current policy and direction
  do not share.
- What is not sent leaves no residue: the queue lets the job go, and the row waits needing a job, so the top-up uploads
  it if the photo is shared again.

**Non-Goals:**
- An upload iOS is performing when the member stops sharing (it is not a retry; the withdrawal from the others is
  unchanged).
- The app's own session uploader (already judged at its top-up).
- Withdrawing rows the walk keeps (the window rule is unchanged).

## Decisions

### D1 — The settle judges each retry by the current contribution

`SettlePresentedJobs` takes the pass's `Contribution` (already derived before it, in `ExtensionPass`) instead of the bare
`Presented` mode. For every offered retry and every held failure it asks whether the row's photo is shared now: the
direction shares at all (`policy.contributes`) and the policy admits the row (`admitRecorded` over its `recorded`).
Shared → as today. Not shared → D2.

*Alternative rejected:* withdraw such rows in the walk. That would delete the rows of photos the member un-shares by a
narrowed range — the window rule exists so a raised cutoff never un-shares what already landed — and a declined
membership does not walk at all.

*Alternative rejected:* leave the re-point and drop the bytes at the edge. The bytes still leave the phone, which is what
this change stops.

### D2 — A refused retry is let go, and its row waits

- A refused **offered retry** is acknowledged without a re-point (the drain's existing `answer`, which makes the queue let
  the job go) and its row is recorded returned — needing a job — through `JobRecord`, exactly as a terminal failure is.
- A refused **held failure** is not re-created; the drain already recorded its row returned.
Either way the row is `DISCOVERED` and admitted by nothing until the member shares the photo again; then the next top-up
creates it, as for any photo that becomes shared (requirement "Restoring or re-including a photo shares it again").

### D3 — Declined means refused for all

A membership that shares nothing (`DECLINED`) refuses every retry and every re-creation (D2), where today it re-creates.
`WITHHELD` — this process may not create — is unchanged: it records and acknowledges what the queue presents and creates
nothing.

### D4 — One policy read per settle

The admitted set is computed once per settle over the rows the queue presents, from the one policy of the pass — the same
answer the walk, the top-up and the publish of that pass use, so they cannot disagree within an invocation.

## Risks / Trade-offs

- [A photo re-shared after its retry was let go waits for the next top-up rather than iOS's free retry] → One more pass's
  delay for a photo the member had un-shared; it is never lost.
- [The rows cannot be read to judge a presented job] → The drain already leaves a job whose row cannot be read
  unanswered for the queue to present again; the judgement runs only over rows it found.

## Migration Plan

None: no stored state changes. Rollback is reverting the commit.

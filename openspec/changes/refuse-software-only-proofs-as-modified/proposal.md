# Proposal

## Why

A refused Android phone is told one of three causes, and only "could not be verified" (the proof its hardware gives
is not one the service recognises) offers to report the problem. That report exists to catch a real proof nobody
recognised, which may be ours to fix. Today it is buried: a proof that only software produced, which anyone can make
and the service can never accept, also lands there. Every one of the 134 such reports received so far (SNAPSYNC-45's
133, SNAPSYNC-65's 1) came from automated testing on Play uploads, from emulators: some posing as a OnePlus 8 Pro or
an Honor 10 Lite, the rest stock emulators. Each one ended on the create screen with the pre-written report sent.

## What Changes

- A phone whose hardware gives only a software proof is told its **system is modified**, not that it could not be
  verified. That covers an emulator and a real phone whose security hardware no longer signs (its factory keys
  erased, or a system its maker did not ship). As for any modified phone, it is offered no report.
- "Could not be verified" stays for a proof that is neither recognised nor known to be software-only. It keeps its
  wording and its report offer.
- Nothing is admitted that was refused before, and nothing refused that was admitted: only the cause a refused
  phone is told changes.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `privacy-security`: "A refused phone is told why". The modified-system cause now also covers a phone whose
  hardware gives only a software proof, and a scenario says so. The three screens that show a refusal
  (`create-event`, `join-event`, `sync-status`) name this requirement for its causes, so they need no delta.

## Impact

- **api**: the Android attestation verifier, where commit `cd7791966` already recognises the two software-only roots
  and names them as the refusal's diagnostic detail. Here they change from the default reason to the modified one.
- **app**: none. Shipped apps of both platforms already show the modified cause, with no report offer, for that
  reason.
- **docs**: `docs/architecture.md`'s attestation route row.
- **Operations**: the pre-written reports from automated testing stop. SNAPSYNC-45 and SNAPSYNC-65 can be resolved
  once this ships.

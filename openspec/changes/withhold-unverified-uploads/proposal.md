# Proposal

## Why

A phone that holds no proof that it is a genuine SnapSync app still sends its photos: the upload goes out without a
proof, the service refuses it, and it is retried until the phone is verified again. Nothing is lost, but a photo's bytes
leave the phone on a request that cannot succeed — spending the member's mobile data and battery — while the status line
already says sharing is paused. It happens mainly in iOS's background uploader after the service refused its proof: that
uploader cannot obtain a new one, so every pass sends refused uploads until the member next opens the app.

## What Changes

- An upload is sent only with a proof. A phone that holds none first tries to obtain one where it can (the app can; iOS's
  background uploader cannot), and when it still has none, the photo waits on the phone instead of being sent.
- A photo that waits this way is not lost and not delayed beyond the verification: it is sent, with a proof, by the first
  pass after the phone is verified again — the existing promise "A verification problem never loses a photo".
- A proof the phone holds is still sent as today, even when the service may judge it expired: the service stays the one
  judge of a proof, and "A renewal fails while the proof is still valid" is unchanged.
- Out of scope: an outdated app's uploads (refused by the service until the update), which are capability
  `app-update-required`'s.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: "A verification problem never loses a photo" gains the promise that a photo is not sent while the
  phone holds no proof, and a scenario for iOS's background uploader after its proof was refused.

## Impact

- The upload request is built only with a proof (the request builder that phase 4.4b moves into the data layer); a
  missing one is a withheld outcome on both transfer tiers, never a request without one.
- iOS's background uploader rests while it holds no proof; the app, once verified, uploads what waited.
- Integration test over the background uploader with its proof refused; unit tests of the request builder and of each
  tier's withheld path.
- Ships inside the phase-4.4b pull request (label `bug`), after the move of the ledger and transfers into the data layer.

# Proposal

## Why

A photo the member stopped sharing can still be uploaded. When iOS's background uploader reports that a photo's upload
failed, the app sends it again — re-creating the job, or accepting iOS's offer to retry it — without asking whether the
photo is still shared. So a photo whose upload failed just before the member narrowed their range, or switched to
receiving only, still leaves the phone on the next pass: no other member is offered it (the device's shared set no longer
lists it), but its bytes reach the service, spending the member's data and contradicting the promise that what the device
uploads is exactly what it shares.

## What Changes

- A failed upload is sent again only while the photo is still shared: by the member's current range, selection and
  exclusions, and only while they share at all. Otherwise it is not retried, and it waits on the phone like any photo the
  member does not share.
- A photo that waits this way is uploaded again if the member shares it again — widening the range back, or switching
  sharing back on — by the same path as any photo that becomes shared (requirement "Restoring or re-including a photo
  shares it again", unchanged).
- Out of scope: an upload already in progress when the member stops sharing (iOS performs it; the photo is withdrawn from
  the others as today, requirement "Deleting or no longer sharing a photo withdraws it"), and the app's own uploader,
  whose retries already go through the current choices.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-sharing`: "Deleting or no longer sharing a photo withdraws it" gains the promise that a photo no longer shared is
  not uploaded again after a failed attempt, and a scenario for it.

## Impact

- The extension's settle with iOS's upload queue (phase 4.4b's `SettlePresentedJobs`): every failure it would re-create
  and every retry iOS offers is first judged by the membership's current policy and direction; a refused one is not sent —
  an offered retry is declined, a failure is not re-created — and its row waits needing a job.
- Unit tests of the settle use case; an integration test over the background uploader.
- Ships inside the phase-4.4b pull request (label `bug`).

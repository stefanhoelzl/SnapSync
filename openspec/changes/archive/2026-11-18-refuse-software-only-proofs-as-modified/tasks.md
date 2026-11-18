# Tasks

## 1. The api refuses software-only proofs as modified

- [x] 1.1 In `api/src/android-attest.ts`, make the software-only-root refusal carry `device-modified` (detail unchanged:
  `software-root` / `emulator-root`), and update its KDoc and the verifier's header comment. The first commit carries
  `Bugsink-Resolves: SNAPSYNC-45` and `Bugsink-Resolves: SNAPSYNC-65`. Verify: `deno task check` passes.
- [x] 1.2 Update `api/test/android-attest.test.ts` ("a chain ending at AOSP's software root…") to expect
  `device-modified` / `software-root`, and `api/test/android-emulator-proof.test.ts` to expect the body
  `attestation rejected: device-modified (emulator-root)`. Keep the plain-`certificate` case for a Google root the
  deployment does not pin. Verify: `deno task test` passes in `api/`.
- [x] 1.3 Update `api/src/attest.ts`'s `RefusalDetail` KDoc and `docs/architecture.md`'s `/attest/token` row: the
  software-only roots are now `device-modified`. Verify: `deno fmt --check` and `deno lint` pass in `api/`, and the
  row reads consistently with the verifier.

## 2. Integration

- [x] 2.1 Confirm the app needs no change: the `device-modified` reason maps to the modified message and offers no
  report on both platforms. Verify by reading `IssuerReplies`, the presentation mapping and `offersReport`, and by
  running the existing refusal tests in `./gradlew build`.
- [x] 2.2 Validate the change: `npx --yes @fission-ai/openspec@1.13.2 validate refuse-software-only-proofs-as-modified --strict`
  passes.

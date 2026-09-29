// A proof the REAL Android adapter produced on the emulator (`AndroidDeviceIntegrity`), recorded by
// `:adapter:android`'s `AndroidDeviceIntegrityContractTest` ("it records a proof for the api to replay") and
// copied here UNEDITED from its log lines (`adb logcat -d -s snapsync-recording:I`). It is what closes the
// gap between the two sides' contracts: the adapter's is checked against the adapter, the routes' against
// the api, and only this checks that the bytes one side produces are the bytes the other verifies — how
// the challenge becomes the attestation challenge, the chain's order and encoding, the signature's DER.
//
// Measured with it (API 36 `google_apis` x86_64, 2026-09-29): a SOFTWARE KeyMint 4 attestation (security
// level 0, bootloader unlocked, boot unverified) under a per-AVD "Droid Unregistered Device CA, O=Google
// Test LLC" root; the intermediate is valid 2026-09-27..2026-10-13, so the replay runs at RECORDED_AT.
// The package is the device-test APK's, signed with the debug key.
//
// Re-record when the adapter's encoding changes: the recorder's challenges are `mintChallenge` with the
// harness CONFIG at RECORDED_AT and RECORDED_AT + 1s — move both instants together.

/** The instant the challenges were minted at, inside the chain's validity. */
export const RECORDED_AT = Date.parse("2026-09-29T12:00:00Z");

/** The package the recording names: the device-test APK. */
export const RECORDED_PACKAGE = "app.snapsync.adapter.android.test";

/** `Proof.bytes` of the fresh proof: the chain, leaf first, as concatenated DER, base64. */
export const ATTESTATION =
  "MIIC1jCCAnygAwIBAgIBATAKBggqhkjOPQQDAjA5MQwwCgYDVQQKEwNURUUxKTAnBgNVBAMTIGU1YThjYzVjMjVlMmVlNjg3ZmRmYjZkN2ZhZGUyZTMzMB4XDTcwMDEwMTAwMDAwMFoXDTQ4MDEwMTAwMDAwMFowHzEdMBsGA1UEAxMUQW5kcm9pZCBLZXlzdG9yZSBLZXkwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASPiIrf0ZyAXOearGogDMOIZlsg3JNELl7mufhQRlSO4OBDsxM9PPj/ue9mZTkuDcqbP0TqypIFJ3Fe+vnpW4iNo4IBjTCCAYkwDgYDVR0PAQH/BAQDAgeAMIIBdQYKKwYBBAHWeQIBEQSCAWUwggFhAgIBkAoBAAICAZAKAQAEIFgumb2GwEBRder6iOfAkJgur+1lkyjwEtYg0/HPAyvQBAAwggEpoQUxAwIBAqIDAgEDowQCAgEApQUxAwIBBKoDAgEBv4N3AgUAv4U9CAIGAaDuQu3ev4U+AwIBAL+FQEwwSgQgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAQAKAQIEIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAv4VBBQIDAnEAv4VCBQIDAxcLv4VFUgRQME4xKDAmBCFhcHAuc25hcHN5bmMuYWRhcHRlci5hbmRyb2lkLnRlc3QCAQAxIgQgcGK/YDPOCl1wsSILVNER9gOIBCaOb7oM0dkGi6wpBcu/hU4GAgQBNQBRv4VPBgIEATUATb+FVCIEIPA9dZhu2zoTAjt9LC8G3qHO0SR4bqoJ/uIZuVLaGXb2MAAwCgYIKoZIzj0EAwIDSAAwRQIgDrZPN4mvUVVvE/0g2bGbAVkMzQEC0W5KzNUrjzfbtRYCIQDOKt6dMZsbRyg4DLdtEw570+6oCsmZ0joGiN94tCOJCjCCAe8wggGVoAMCAQICEQDlqMxcJeLuaH/fttf63i4zMAoGCCqGSM49BAMCMEExJTAjBgNVBAMTHERyb2lkIFVucmVnaXN0ZXJlZCBEZXZpY2UgQ0ExGDAWBgNVBAoTD0dvb2dsZSBUZXN0IExMQzAeFw0yNjA5MjcxMDA1NTdaFw0yNjEwMTMxMDIxMTBaMDkxDDAKBgNVBAoTA1RFRTEpMCcGA1UEAxMgZTVhOGNjNWMyNWUyZWU2ODdmZGZiNmQ3ZmFkZTJlMzMwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASa6InC+uLLq/9CkS/NchBtst5omBt5xUVvBkKUvNg4K4XVKyisY4mQKXqMdROdVW4XlgNIZK3YsuMO8xW7AqvBo3YwdDAdBgNVHQ4EFgQUP9dTavZJ0LtEZDNgs2CYrMTIOKkwHwYDVR0jBBgwFoAUKVeFI617W/sOaQk1lKULtEXiG20wDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQwEQYKKwYBBAHWeQIBHgQDoQEIMAoGCCqGSM49BAMCA0gAMEUCICbE6/o5jWN/0P0Izp+mUFcQhMILEFubyPStfxoIppKRAiEA0OJkFIATjc0mjAtJOMhdVVXwsTSuI81rwRzCeNEeSWgwggH2MIIBnKADAgECAhB3uKgrqsVVlJn1D38HjNVAMAoGCCqGSM49BAMCMEExJTAjBgNVBAMTHERyb2lkIFVucmVnaXN0ZXJlZCBEZXZpY2UgQ0ExGDAWBgNVBAoTD0dvb2dsZSBUZXN0IExMQzAeFw0yNjA5MjcwMTA5MTlaFw0yNjEyMDgwMTMyMzJaMEExJTAjBgNVBAMTHERyb2lkIFVucmVnaXN0ZXJlZCBEZXZpY2UgQ0ExGDAWBgNVBAoTD0dvb2dsZSBUZXN0IExMQzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABA51TRywaATsyQkRgae9LwM5bZfB4JhpSsRyKaX1BKBjJ/z+sVBBnXQ6DNkP7GJpvsCywaKAeD6mqqWooGpSuoOjdjB0MB0GA1UdDgQWBBQpV4UjrXtb+w5pCTWUpQu0ReIbbTAfBgNVHSMEGDAWgBQpV4UjrXtb+w5pCTWUpQu0ReIbbTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDARBgorBgEEAdZ5AgEeBAOhAQAwCgYIKoZIzj0EAwIDSAAwRQIgV9tzTzgFKocRLoLd9gbqZSkwveNJ4qF2ynXczsx7incCIQC51dxaVyFZM/xv9wY94VPEeBErn5I1qlcovjLy1I97yQ==";

/** `Proof.bytes` of the renewal over RECORDED_AT + 1s's challenge: a DER ECDSA signature, base64. */
export const RENEWAL =
  "MEYCIQDcecM1/A3IlyUQi6bpwBCdp2x0HehF0EEiVDhnHxy/OQIhAPSFwkoL/TrfBTHgCNmgiH1cSEraz0hnXHE3MUB0gVsM";

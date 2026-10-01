#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["google-auth", "requests"]
# ///
"""Deliver an Android App Bundle to Google Play over the Play Developer API (v3).

`ci.yml`'s `android-deliver` uploads every delivering run's signed bundle to the INTERNAL testing track, as
`ios-deliver` uploads to internal TestFlight; `promote.yml` releases an ALREADY-UPLOADED bundle to a further track.
Every Play change happens inside ONE EDIT, committed once at the end or deleted on any failure, so a run either
lands whole or changes nothing. Later steps (a diff-gated listing update) are further operations on the same
`Edit`, not edits of their own.

    status   <package>                         Read-only: every track's releases, then the edit is DELETED.
    has      <package> <versionCode>           Read-only: fails unless a bundle with that versionCode was uploaded
                                               (whatever track holds it now — internal keeps only its latest).
    deliver  <package> <bundle> <track> <name> <note>
             Upload the bundle, make it the track's one release named <name> (status `completed`, the note as
             its en-US release notes, cut to Play's 500 characters), commit.
    promote  <package> <track> <versionCode> <name> <note-file> (validate|commit)
             Make the already-uploaded <versionCode> the track's one release named <name> (status `completed`,
             the file's text as its en-US release notes — REFUSED over 500 characters, never cut), then either
             ask Play to VALIDATE the edit and delete it (the preflight), or COMMIT it. A track that already
             carries <versionCode> as a completed release is left alone (`released=true` to $GITHUB_OUTPUT), so a
             rerun of a half-done promote skips what landed.

WHY PREFLIGHT AND COMMIT ARE TWO EDITS: a workflow's steps are separate processes, and every merge's
`android-deliver` commits an edit of its own in between, which leaves an edit opened earlier uncommittable. So the
preflight's edit is thrown away, and the commit opens a fresh one; a merge landing in the gap fails the commit, and
the rerun completes it.

Credentials: the service account's JSON key, from PLAY_SERVICE_ACCOUNT_JSON (the CI secret) or
PLAY_SERVICE_ACCOUNT_KEY (its secrets-env name). The key is read from the environment only, never a file.
"""

from __future__ import annotations

import json
import os
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"

# Play's limit on one language's release notes.
NOTE_LIMIT = 500
# The listing's default language; release notes in any other are refused.
NOTE_LANGUAGE = "en-US"
# A bundle is tens of MB; the upload gets far longer than an API call.
CALL_TIMEOUT = 60
UPLOAD_TIMEOUT = 900


def session() -> AuthorizedSession:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON") or os.environ.get("PLAY_SERVICE_ACCOUNT_KEY")
    if not raw:
        sys.exit("error: neither PLAY_SERVICE_ACCOUNT_JSON nor PLAY_SERVICE_ACCOUNT_KEY is set")
    creds = service_account.Credentials.from_service_account_info(json.loads(raw), scopes=[SCOPE])
    return AuthorizedSession(creds)


def checked(response):
    if not response.ok:
        raise RuntimeError(f"{response.request.method} {response.url} → {response.status_code}: {response.text}")
    return response.json() if response.content else {}


class Edit:
    """One Play edit: opened on entry; committed by [commit], deleted on exit if it was not."""

    def __init__(self, http: AuthorizedSession, package: str):
        self.http = http
        self.package = package
        self.id: str | None = None
        self.committed = False

    def __enter__(self) -> Edit:
        self.id = checked(self.http.post(f"{API}/{self.package}/edits", timeout=CALL_TIMEOUT))["id"]
        print(f"opened edit {self.id}")
        return self

    def __exit__(self, *_exc) -> None:
        if not self.committed and self.id is not None:
            self.http.delete(self.url(), timeout=CALL_TIMEOUT)
            print(f"deleted edit {self.id} (nothing committed)")

    def url(self, path: str = "") -> str:
        return f"{API}/{self.package}/edits/{self.id}{path}"

    def tracks(self) -> list[dict]:
        return checked(self.http.get(self.url("/tracks"), timeout=CALL_TIMEOUT)).get("tracks", [])

    def upload_bundle(self, path: str) -> int:
        with open(path, "rb") as bundle:
            uploaded = checked(self.http.post(
                f"{UPLOAD_API}/{self.package}/edits/{self.id}/bundles",
                params={"uploadType": "media"},
                headers={"Content-Type": "application/octet-stream"},
                data=bundle,
                timeout=UPLOAD_TIMEOUT,
            ))
        print(f"uploaded bundle: versionCode {uploaded['versionCode']}, sha256 {uploaded.get('sha256')}")
        return int(uploaded["versionCode"])

    def release_to(self, track: str, version_code: int, name: str, note: str) -> None:
        body = {
            "track": track,
            "releases": [{
                "name": name,
                "versionCodes": [str(version_code)],
                "status": "completed",
                "releaseNotes": [{"language": NOTE_LANGUAGE, "text": note}],
            }],
        }
        checked(self.http.put(self.url(f"/tracks/{track}"), json=body, timeout=CALL_TIMEOUT))
        print(f"track {track}: release {name} ({version_code}), completed")

    def bundle_codes(self) -> set[int]:
        listed = checked(self.http.get(self.url("/bundles"), timeout=CALL_TIMEOUT)).get("bundles", [])
        return {int(bundle["versionCode"]) for bundle in listed}

    def released(self, track: str, version_code: int) -> bool:
        """Whether the track already carries <version_code> as a completed release."""
        for entry in self.tracks():
            if entry["track"] != track:
                continue
            for release in entry.get("releases", []):
                if release.get("status") == "completed" and str(version_code) in release.get("versionCodes", []):
                    return True
        return False

    def validate(self) -> None:
        checked(self.http.post(self.url(":validate"), timeout=CALL_TIMEOUT))
        print(f"validated edit {self.id}")

    def commit(self) -> None:
        checked(self.http.post(self.url(":commit"), timeout=CALL_TIMEOUT))
        self.committed = True
        print(f"committed edit {self.id}")


def clip(note: str) -> str:
    return note if len(note) <= NOTE_LIMIT else note[: NOTE_LIMIT - 1] + "…"


def status(package: str) -> None:
    with Edit(session(), package) as edit:
        for track in edit.tracks():
            print(f"{track['track']}:")
            for release in track.get("releases", []):
                print(f"  {release.get('name')}  status={release.get('status')}  "
                      f"versionCodes={release.get('versionCodes')}")


def has(package: str, version_code: int) -> None:
    with Edit(session(), package) as edit:
        if version_code not in edit.bundle_codes():
            sys.exit(f"::error::Play holds no bundle with versionCode {version_code} for {package}")
        print(f"Play holds versionCode {version_code}")


def output(key: str, value: str) -> None:
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as handle:
            handle.write(f"{key}={value}\n")


def promote(package: str, track: str, version_code: int, name: str, note_file: str, mode: str) -> None:
    with open(note_file, encoding="utf-8") as handle:
        note = handle.read().strip()
    if not note or len(note) > NOTE_LIMIT:
        sys.exit(f"::error::the Play release note is {len(note)} characters; it must be 1..{NOTE_LIMIT}")
    with Edit(session(), package) as edit:
        if edit.released(track, version_code):
            print(f"track {track} already carries {version_code} as a completed release — nothing to do")
            output("released", "true")
            return
        edit.release_to(track, version_code, name=name, note=note)
        if mode == "validate":
            edit.validate()
        else:
            edit.commit()
    output("released", "false")


def deliver(package: str, bundle: str, track: str, name: str, note: str) -> None:
    with Edit(session(), package) as edit:
        version_code = edit.upload_bundle(bundle)
        edit.release_to(track, version_code, name=name, note=clip(note))
        edit.commit()


def main(argv: list[str]) -> None:
    match argv:
        case ["status", package]:
            status(package)
        case ["has", package, version_code]:
            has(package, int(version_code))
        case ["deliver", package, bundle, track, name, note]:
            deliver(package, bundle, track, name, note)
        case ["promote", package, track, version_code, name, note_file, ("validate" | "commit") as mode]:
            promote(package, track, int(version_code), name, note_file, mode)
        case _:
            sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])

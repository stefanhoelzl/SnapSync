#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["google-auth", "requests"]
# ///
"""Deliver an Android App Bundle to Google Play over the Play Developer API (v3).

`ci.yml`'s `android-deliver` uploads every push to main's signed bundle to the INTERNAL testing track, as
`ios-deliver` uploads to internal TestFlight, and a branch dispatch's through INTERNAL APP SHARING (`share`), which
touches no track; `promote.yml` releases an ALREADY-UPLOADED bundle to a further track.
Every track or listing change happens inside ONE EDIT, committed once at the end or deleted on any failure, so a run either
lands whole or changes nothing. Later steps (the diff-gated listing update) are further operations on the same
`Edit`, not edits of their own: committing one edit invalidates every other open one, so two would race.

    status   <package>                         Read-only: every track's releases, then the edit is DELETED.
    has      <package> <versionCode>           Read-only: fails unless a bundle with that versionCode was uploaded
                                               (whatever track holds it now — internal keeps only its latest).
    deliver  <package> <bundle> <track> <name> <note> [--listing <dir> --images <dir>]
             Upload the bundle, make it the track's one release named <name> (status `completed`, the note as
             its en-US release notes, cut to Play's 500 characters), bring the store listing in line with
             <dir>s when given (below), commit.
    share    <package> <bundle> <android component>
             Upload the bundle through internal app sharing — no edit, no track, nothing committed — and print its
             install link (`link=` to $GITHUB_OUTPUT too). Play re-signs a sharing build with its own key: fails
             unless that certificate's digest is in the component's `androidSigningCertDigests`, since prod refuses
             the attestation of an app signed with any other, so the link would install an app that cannot join.
    listing-diff <package> <listing dir> <images dir> [--try]
             What `deliver` would change in the store listing, then the edit is DELETED. With --try the changes
             are made too — Play validates them — and still never committed.
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

THE STORE LISTING (`docs/deployment.md`, "Listing metadata"). <listing dir> holds the rendered Play listing,
`<language>.json` (title, shortDescription, fullDescription, contactWebsite: scripts/resolve-deployment.py renders it
from metadata/listing/); <images dir> holds icon.png, featureGraphic.png and phoneScreenshots/*.png, in upload order.
Each text field, the contact details and each image set is compared with what Play holds — text by value, images by
the sha256 Play lists, which is the uploaded file's own (measured: Play keeps the bytes) — and only a difference is
written. So a merge that changes no copy and no image sends Play nothing to review. PLAY_CONTACT_EMAIL, when set, is the
listing's contact email (a secret: it never enters the repo, and is never printed).

Credentials: the service account's JSON key, from PLAY_SERVICE_ACCOUNT_JSON (the CI secret) or
PLAY_SERVICE_ACCOUNT_KEY (its secrets-env name). The key is read from the environment only, never a file.
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
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
IMAGE_TIMEOUT = 300

# The listing's text fields, by the names Play's listing resource uses (and the rendered file carries).
LISTING_FIELDS = ("title", "shortDescription", "fullDescription")
# The image sets the listing carries, in the order they are reported.
IMAGE_TYPES = ("icon", "featureGraphic", "phoneScreenshots")


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

    def listing(self, language: str) -> dict:
        response = self.http.get(self.url(f"/listings/{language}"), timeout=CALL_TIMEOUT)
        # A language with no listing yet answers 404: nothing is there to match.
        return {} if response.status_code == 404 else checked(response)

    def patch_listing(self, language: str, fields: dict) -> None:
        checked(self.http.patch(self.url(f"/listings/{language}"), json=fields, timeout=CALL_TIMEOUT))

    def details(self) -> dict:
        return checked(self.http.get(self.url("/details"), timeout=CALL_TIMEOUT))

    def patch_details(self, fields: dict) -> None:
        checked(self.http.patch(self.url("/details"), json=fields, timeout=CALL_TIMEOUT))

    def image_hashes(self, language: str, kind: str) -> list[str]:
        listed = checked(self.http.get(self.url(f"/listings/{language}/{kind}"), timeout=CALL_TIMEOUT))
        return [image["sha256"] for image in listed.get("images", [])]

    def replace_images(self, language: str, kind: str, files: list[pathlib.Path]) -> None:
        """Delete the set, then upload [files] in order: Play shows a set in the order it was uploaded."""
        checked(self.http.delete(self.url(f"/listings/{language}/{kind}"), timeout=CALL_TIMEOUT))
        for path in files:
            checked(self.http.post(
                f"{UPLOAD_API}/{self.package}/edits/{self.id}/listings/{language}/{kind}",
                params={"uploadType": "media"},
                headers={"Content-Type": "image/png"},
                data=path.read_bytes(),
                timeout=IMAGE_TIMEOUT,
            ))

    def commit(self) -> None:
        checked(self.http.post(self.url(":commit"), timeout=CALL_TIMEOUT))
        self.committed = True
        print(f"committed edit {self.id}")


def rendered_listing(listing_dir: str) -> tuple[str, dict]:
    """The one rendered language's listing: (language, fields). Play's listing is en-US only today."""
    files = sorted(pathlib.Path(listing_dir).glob("*.json"))
    if len(files) != 1:
        sys.exit(f"error: expected exactly one rendered listing in {listing_dir}, found {[f.name for f in files]}")
    return files[0].stem, json.loads(files[0].read_text())


def image_sets(images_dir: str) -> dict[str, list[pathlib.Path]]:
    root = pathlib.Path(images_dir)
    sets = {
        "icon": [root / "icon.png"],
        "featureGraphic": [root / "featureGraphic.png"],
        "phoneScreenshots": sorted((root / "phoneScreenshots").glob("*.png")),
    }
    for kind, files in sets.items():
        missing = [str(f) for f in files if not f.is_file()]
        if not files or missing:
            sys.exit(f"error: the {kind} image set is incomplete in {images_dir} (missing {missing or 'every file'})")
    return sets


def sync_listing(edit: Edit, listing_dir: str, images_dir: str, write: bool) -> list[str]:
    """Bring Play's listing in line with the rendered one; answer what differed. With write=False only compare."""
    language, rendered = rendered_listing(listing_dir)
    changed = []

    held = edit.listing(language)
    text = {f: rendered[f] for f in LISTING_FIELDS if held.get(f) != rendered[f]}
    for field in LISTING_FIELDS:
        print(f"listing {language} {field}: {'updated' if field in text else 'unchanged'}")
    if text:
        changed.append("text")
        if write:
            edit.patch_listing(language, {f: rendered[f] for f in LISTING_FIELDS})

    wanted = {"contactWebsite": rendered["contactWebsite"]}
    email = os.environ.get("PLAY_CONTACT_EMAIL", "").strip()
    if email:
        wanted["contactEmail"] = email
    held = edit.details()
    differing = [f for f, v in wanted.items() if held.get(f) != v]
    for field in wanted:  # names only: the email's value is never printed
        print(f"details {field}: {'updated' if field in differing else 'unchanged'}")
    if differing:
        changed.append("details")
        if write:
            edit.patch_details(wanted)

    for kind, files in image_sets(images_dir).items():
        ours = [hashlib.sha256(f.read_bytes()).hexdigest() for f in files]
        same = edit.image_hashes(language, kind) == ours
        print(f"images {language} {kind} ({len(files)}): {'unchanged' if same else 'updated'}")
        if not same:
            changed.append(kind)
            if write:
                edit.replace_images(language, kind, files)
    return changed


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


def deliver(package: str, bundle: str, track: str, name: str, note: str,
            listing: str | None = None, images: str | None = None) -> None:
    with Edit(session(), package) as edit:
        version_code = edit.upload_bundle(bundle)
        edit.release_to(track, version_code, name=name, note=clip(note))
        if listing and images:
            changed = sync_listing(edit, listing, images, write=True)
            print(f"store listing: {', '.join(changed) + ' updated' if changed else 'unchanged, nothing sent'}")
        edit.commit()


def hex_digits(fingerprint: str) -> str:
    return "".join(c for c in fingerprint.lower() if c in "0123456789abcdef")


def share(package: str, bundle: str, component: str) -> None:
    with open(component, encoding="utf-8") as handle:
        accepted = {hex_digits(d) for d in json.load(handle)["androidSigningCertDigests"]}
    with open(bundle, "rb") as data:
        artifact = checked(session().post(
            f"{UPLOAD_API}/internalappsharing/{package}/artifacts/bundle",
            params={"uploadType": "media"},
            headers={"Content-Type": "application/octet-stream"},
            data=data,
            timeout=UPLOAD_TIMEOUT,
        ))
    link, fingerprint = artifact["downloadUrl"], artifact.get("certificateFingerprint", "")
    print(f"shared bundle: sha256 {artifact.get('sha256')}, signed by {fingerprint}")
    output("link", link)
    if hex_digits(fingerprint) not in accepted:
        sys.exit(f"::error::Play signed the shared build with {fingerprint!r}, which {component}'s "
                 f"androidSigningCertDigests does not hold: prod would refuse its attestation. Link: {link}")
    print(f"link: {link}")


def listing_diff(package: str, listing: str, images: str, write: bool) -> None:
    with Edit(session(), package) as edit:
        changed = sync_listing(edit, listing, images, write=write)
        print(f"store listing: {', '.join(changed) + ' would be updated' if changed else 'unchanged'}"
              + (" (written into the edit, which is now deleted)" if write and changed else ""))


def main(argv: list[str]) -> None:
    match argv:
        case ["status", package]:
            status(package)
        case ["has", package, version_code]:
            has(package, int(version_code))
        case ["deliver", package, bundle, track, name, note]:
            deliver(package, bundle, track, name, note)
        case ["deliver", package, bundle, track, name, note, "--listing", listing, "--images", images]:
            deliver(package, bundle, track, name, note, listing, images)
        case ["share", package, bundle, component]:
            share(package, bundle, component)
        case ["listing-diff", package, listing, images]:
            listing_diff(package, listing, images, write=False)
        case ["listing-diff", package, listing, images, "--try"]:
            listing_diff(package, listing, images, write=True)
        case ["promote", package, track, version_code, name, note_file, ("validate" | "commit") as mode]:
            promote(package, track, int(version_code), name, note_file, mode)
        case _:
            sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])

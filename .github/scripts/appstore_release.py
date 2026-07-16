#!/usr/bin/env python3
"""Attach a tag-built iOS build to its App Store version record.

`ios-release.yml` uploads an `X.Y` build to App Store Connect and then runs this to make the "1.0"-style
version record actually reference that build — the last step of "version + build submission-ready". A
human clicks Submit later (once the listing / screenshots / privacy, owned elsewhere, are complete).

The codemagic `app-store-connect` CLI has no version-record attach subcommand, so this drops to the ASC
REST API (same ES256 JWT + Admin key the rest of the pipeline uses — ASC_KEY_ID / ASC_ISSUER_ID /
ASC_API_PRIVATE_KEY — so it adds no new credential), mirroring `testflight_promote.py`.

    release  Resolve the just-uploaded build by its build NUMBER (retried — a fresh upload is not
             immediately discoverable), FIND-OR-CREATE the App Store version record whose
             versionString == the store version (platform IOS), and ATTACH the build to it. It STOPS
             before submit-for-review. Idempotent: a record that already references this build is a
             green no-op, so re-running a flaked release is safe.
"""

from __future__ import annotations

import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"

# A just-uploaded build is neither immediately discoverable NOR immediately VALID; attaching a build to
# an App Store version requires it to be processed (VALID), so we wait for both. Generous because App
# Store processing can lag; on timeout the job fails red and is safe to re-run (idempotent).
FIND_TIMEOUT_S = 20 * 60
FIND_POLL_S = 20

# Applied only when this script CREATES the version record (below) — an existing record is reused
# untouched, so a copyright set by hand in the console survives. It cannot ride the declarative
# `metadata` push instead: copyright is a version ATTRIBUTE, which that tool's closed schema rejects.
# `YYYY Name`, where YYYY is the year of FIRST PUBLICATION — it does NOT roll with the calendar year.
COPYRIGHT = "2026 Stefan Hoelzl"


def _token() -> str:
    return jwt.encode(
        {
            "iss": os.environ["ASC_ISSUER_ID"],
            "exp": int(time.time()) + 600,
            "aud": "appstoreconnect-v1",
        },
        os.environ["ASC_API_PRIVATE_KEY"],
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"Authorization": f"Bearer {_token()}", "Content-Type": "application/json"})
    return s


def _resolve_build(s: requests.Session, app_id: str, build_number: str) -> str:
    """Wait for the uploaded build (identified by its CFBundleVersion) to be discoverable AND VALID."""
    deadline = time.time() + FIND_TIMEOUT_S
    while True:
        r = s.get(
            f"{API}/builds",
            params={"filter[app]": app_id, "filter[version]": build_number, "limit": 1},
        )
        r.raise_for_status()
        found = r.json()["data"]
        if found:
            build = found[0]
            state = build["attributes"].get("processingState")
            if state == "VALID":
                print(f"build {build_number} = {build['id']} (VALID)")
                return build["id"]
            print(f"build {build_number} found but processingState={state}; waiting {FIND_POLL_S}s")
        else:
            print(f"build {build_number} not discoverable yet; retrying in {FIND_POLL_S}s")
        if time.time() >= deadline:
            raise SystemExit(
                f"::error::build {build_number} never became discoverable and VALID in App Store "
                f"Connect within {FIND_TIMEOUT_S // 60} min"
            )
        time.sleep(FIND_POLL_S)


def _find_or_create_version(s: requests.Session, app_id: str, version_string: str) -> str:
    """Return the id of the iOS App Store version record for `version_string`, creating it if absent."""
    r = s.get(
        f"{API}/apps/{app_id}/appStoreVersions",
        params={"filter[versionString]": version_string, "filter[platform]": "IOS", "limit": 1},
    )
    r.raise_for_status()
    existing = r.json()["data"]
    if existing:
        vid = existing[0]["id"]
        state = existing[0]["attributes"].get("appStoreState")
        print(f"version '{version_string}' exists = {vid} (state {state}) — reusing")
        return vid

    r = s.post(
        f"{API}/appStoreVersions",
        json={
            "data": {
                "type": "appStoreVersions",
                "attributes": {
                    "platform": "IOS",
                    "versionString": version_string,
                    "copyright": COPYRIGHT,
                },
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }
        },
    )
    if r.status_code == 409:
        # App Store Connect allows only ONE editable version at a time. If a prior version is still
        # editable/unreleased, creating a new one is refused — release that one first.
        raise SystemExit(
            f"::error::cannot create App Store version '{version_string}': another version is likely "
            f"still editable (ASC allows one at a time). Release/clear it first. Response: {r.text}"
        )
    r.raise_for_status()
    vid = r.json()["data"]["id"]
    print(f"version '{version_string}' created = {vid}")
    return vid


def _attached_build_id(s: requests.Session, version_id: str) -> str | None:
    """The build id currently attached to this version record, or None."""
    r = s.get(f"{API}/appStoreVersions/{version_id}/relationships/build")
    r.raise_for_status()
    data = r.json().get("data")
    return data["id"] if data else None


def release(app_id: str, build_number: str, version_string: str) -> None:
    s = _session()
    build_id = _resolve_build(s, app_id, build_number)
    version_id = _find_or_create_version(s, app_id, version_string)

    # Idempotency: already attached => nothing to do.
    if _attached_build_id(s, version_id) == build_id:
        print(f"build {build_id} already attached to version '{version_string}' — nothing to do")
        return

    r = s.patch(
        f"{API}/appStoreVersions/{version_id}/relationships/build",
        json={"data": {"type": "builds", "id": build_id}},
    )
    r.raise_for_status()
    print(f"attached build {build_id} to App Store version '{version_string}' ({version_id})")
    print("::notice::build attached — the version is NOT submitted for review (a human does that)")


if __name__ == "__main__":
    match sys.argv[1:]:
        case ["release", app_id, build_number, version_string]:
            release(app_id, build_number, version_string)
        case _:
            raise SystemExit(
                "usage: appstore_release.py release <app-id> <build-number> <version-string>"
            )

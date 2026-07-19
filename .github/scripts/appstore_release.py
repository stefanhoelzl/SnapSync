#!/usr/bin/env python3
"""Promote an existing App Store Connect build to its App Store version record.

`ios-appstore-promote.yml` PROMOTES a build that `ios-deliver` already uploaded (it builds nothing). The
build is chosen by its build NUMBER (CFBundleVersion); the store version is DERIVED from the build's own
marketing version (`preReleaseVersion.version`), so the version record and the build always match — there
is no `version` input and no mismatch to guard.

The codemagic `app-store-connect` CLI has no version-record attach subcommand, so this drops to the ASC
REST API (same ES256 JWT + Admin key the rest of the pipeline uses — ASC_KEY_ID / ASC_ISSUER_ID /
ASC_API_PRIVATE_KEY — so it adds no new credential).

    resolve  Resolve the build by its build NUMBER, read its marketing version, validate it is two-part
             `X.Y`, and emit it (to $GITHUB_OUTPUT as `version=X.Y` if set, else stdout). Makes NO
             mutation — the workflow calls this FIRST so the tag-absent guard runs before any attach.
    release  Resolve the build (retried — waits until it is VALID), FIND-OR-CREATE the App Store version
             record whose versionString == the DERIVED store version (platform IOS), and ATTACH the build
             to it. Stops before submit-for-review. Idempotent: a record already referencing this build
             is a green no-op, so re-running a flaked release is safe.
"""

from __future__ import annotations

import os
import re
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


def _build_version(build: dict, included: list) -> str:
    """The build's own marketing version (preReleaseVersion.version) = the DERIVED store version."""
    rel = build.get("relationships", {}).get("preReleaseVersion", {}).get("data")
    if not rel:
        raise SystemExit("::error::build has no preReleaseVersion — cannot derive the store version")
    for inc in included:
        if inc.get("type") == "preReleaseVersions" and inc.get("id") == rel["id"]:
            v = inc.get("attributes", {}).get("version")
            if not v:
                raise SystemExit("::error::preReleaseVersion carries no version string")
            return v
    raise SystemExit("::error::preReleaseVersion was not included in the builds response")


def _resolve_build(s: requests.Session, app_id: str, build_number: str, wait: bool) -> tuple[str, str]:
    """Resolve the build by its CFBundleVersion → (build id, derived store version).

    When `wait`, poll until it is discoverable AND `processingState` VALID (needed before an attach). An
    already-uploaded build being promoted is normally VALID immediately, so `resolve` passes wait=False.
    """
    deadline = time.time() + FIND_TIMEOUT_S
    while True:
        r = s.get(
            f"{API}/builds",
            params={
                "filter[app]": app_id,
                "filter[version]": build_number,
                "include": "preReleaseVersion",
                "limit": 1,
            },
        )
        r.raise_for_status()
        body = r.json()
        found = body["data"]
        if found:
            build = found[0]
            version = _build_version(build, body.get("included", []))
            state = build["attributes"].get("processingState")
            if not wait or state == "VALID":
                print(f"build {build_number} = {build['id']} (version {version}, processingState {state})")
                return build["id"], version
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


def _validate_store_version(version: str) -> str:
    """The derived store version must be two-part `X.Y` (capability ios-appstore-release)."""
    if not re.fullmatch(r"\d+\.\d+", version):
        raise SystemExit(
            f"::error::build's marketing version '{version}' is not two-part X.Y — refusing to release "
            f"(a pre-change 0.1.0 or malformed build cannot be promoted)"
        )
    return version


def resolve(app_id: str, build_number: str) -> None:
    """Emit the DERIVED store version (no mutation), so the workflow can guard before attaching."""
    s = _session()
    _, version = _resolve_build(s, app_id, build_number, wait=False)
    _validate_store_version(version)
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"version={version}\n")
    print(version)


def release(app_id: str, build_number: str) -> None:
    s = _session()
    build_id, version_string = _resolve_build(s, app_id, build_number, wait=True)
    _validate_store_version(version_string)
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
        case ["resolve", app_id, build_number]:
            resolve(app_id, build_number)
        case ["release", app_id, build_number]:
            release(app_id, build_number)
        case _:
            raise SystemExit(
                "usage: appstore_release.py (resolve|release) <app-id> <build-number>"
            )

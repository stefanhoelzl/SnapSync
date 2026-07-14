#!/usr/bin/env python3
"""The two halves of TestFlight promotion that the `app-store-connect` CLI cannot reach.

Everything else `ios-promote` does (release note, submit-to-TestFlight, add-to-group) is a
codemagic-cli-tools subcommand. Two things are not:

  resolve  A freshly uploaded build is NOT immediately discoverable in App Store Connect, so the
           lookup has to be RETRIED. `builds list` would just return empty and exit 0. This also
           carries the IDEMPOTENCY GUARD: a build already BETA_APPROVED *and* already in the target
           group is a green no-op, so re-running a flaked promotion is always safe.

  silence  `autoNotifyEnabled` lives on buildBetaDetails and the CLI exposes no flag for it. A plain
           PATCH sets it. This is what keeps TestFlight from pushing a notification to every public
           alpha tester on every merge to main.

Both authenticate with the same Admin App Store Connect API key the rest of the pipeline uses
(ASC_KEY_ID / ASC_ISSUER_ID / ASC_API_PRIVATE_KEY), so this script adds no new credential.
"""

from __future__ import annotations

import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"

# How long to wait for a just-uploaded build to become discoverable. Matches codemagic's own
# --max-find-build-wait default. An estimate, not a measurement — see the change's tasks.
FIND_TIMEOUT_S = 10 * 60
FIND_POLL_S = 20


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


def _emit(**values: str) -> None:
    """Publish values to later steps via $GITHUB_ENV."""
    with open(os.environ["GITHUB_ENV"], "a") as fh:
        for key, value in values.items():
            fh.write(f"{key}={value}\n")


def _group_id(s: requests.Session, app_id: str, name: str) -> str:
    r = s.get(f"{API}/apps/{app_id}/betaGroups")
    r.raise_for_status()
    for group in r.json()["data"]:
        if group["attributes"]["name"] == name:
            return group["id"]
    raise SystemExit(f"::error::no beta group named '{name}' on app {app_id}")


def resolve(app_id: str, version: str, group_name: str) -> None:
    """Wait for the build to appear, then decide whether it still needs promoting."""
    s = _session()
    group = _group_id(s, app_id, group_name)

    deadline = time.time() + FIND_TIMEOUT_S
    while True:
        r = s.get(
            f"{API}/builds",
            params={"filter[app]": app_id, "filter[version]": version, "limit": 1},
        )
        r.raise_for_status()
        found = r.json()["data"]
        if found:
            break
        if time.time() >= deadline:
            raise SystemExit(
                f"::error::build {version} never became discoverable in App Store Connect "
                f"within {FIND_TIMEOUT_S // 60} min"
            )
        print(f"build {version} not discoverable yet; retrying in {FIND_POLL_S}s")
        time.sleep(FIND_POLL_S)

    build_id = found[0]["id"]
    print(f"build {version} = {build_id}")

    # Idempotency: already approved AND already in the group => nothing left to do. Asking App Store
    # Connect for "this build, but only if it is in that group" answers both halves in one call.
    r = s.get(
        f"{API}/builds",
        params={
            "filter[app]": app_id,
            "filter[version]": version,
            "filter[betaGroups]": group,
            "include": "buildBetaDetail",
            "limit": 1,
        },
    )
    r.raise_for_status()
    body = r.json()
    in_group = bool(body["data"])
    state = next(
        (i["attributes"]["externalBuildState"] for i in body.get("included", [])
         if i["type"] == "buildBetaDetails"),
        None,
    )
    already = in_group and state == "BETA_APPROVED"
    if already:
        print(f"build {version} is already BETA_APPROVED and in '{group_name}' — nothing to do")

    _emit(BUILD_ID=build_id, ALREADY_PROMOTED=str(already).lower())


def silence(build_id: str) -> None:
    """Suppress the tester notification. MUST run before the build joins the group."""
    s = _session()
    r = s.patch(
        f"{API}/buildBetaDetails/{build_id}",
        json={
            "data": {
                "type": "buildBetaDetails",
                "id": build_id,
                "attributes": {"autoNotifyEnabled": False},
            }
        },
    )
    r.raise_for_status()
    if r.json()["data"]["attributes"]["autoNotifyEnabled"]:
        raise SystemExit("::error::autoNotifyEnabled is still true — refusing to promote")
    print(f"build {build_id}: autoNotifyEnabled = false")


if __name__ == "__main__":
    match sys.argv[1:]:
        case ["resolve", app_id, version, group_name]:
            resolve(app_id, version, group_name)
        case ["silence", build_id]:
            silence(build_id)
        case _:
            raise SystemExit(
                "usage: testflight_promote.py resolve <app-id> <version> <group>\n"
                "       testflight_promote.py silence <build-id>"
            )

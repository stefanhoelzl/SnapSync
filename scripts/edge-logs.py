#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["websockets"]
# ///
"""Pull the deployed api/'s console log (its `console.*` lines) off bunny Edge Scripting.

    secrets-env scripts/edge-logs.py                     the retained lines, oldest first
    secrets-env scripts/edge-logs.py --level Error       only one level (Info · Error)
    secrets-env scripts/edge-logs.py --zone SG           only one edge location
    secrets-env scripts/edge-logs.py --follow            then keep streaming new lines until ^C
    secrets-env scripts/edge-logs.py --json              the raw records, one per line

Needs `BUNNY_API_KEY` (the account key, which `.secrets.yaml` maps). Dev infra, non-gating.

WHERE THIS COMES FROM. bunny documents no API for a script's log. Only its dashboard's Logs tab shows it,
and that tab reads a websocket, `wss://scripting-logging.bunny.net/<scriptId>?history=N`, which also
accepts the account key as an `AccessKey` header. Found by reading the dashboard's bundle (2026-10-05).
Being undocumented, it may change without notice; a 403 or an empty answer is the first sign.

⚠️ ONLY THE LAST 100 LINES EXIST. bunny keeps a ring of the latest 100 lines per script, not a time
window. `history` above 100 returns 100, `Level=Info` + `Level=Error` sum to the same 100, and the
server ignores any time parameter. So how far back the output reaches depends on how much was logged
since, and a burst of errors pushes everything older out. Run with --follow to keep what scrolls past.
The CDN's request log (status, path, edge, 3 days) is a separate API: logging.bunnycdn.com/v2/pullzones/<id>/logs.
"""

import argparse
import asyncio
import datetime
import json
import os
import sys
import urllib.request

import websockets

API = "https://api.bunny.net"
LOGS = "wss://scripting-logging.bunny.net"
SCRIPT_NAME = "snap-sync"
QUIET = 5.0  # seconds without a message after which the history replay is considered complete


def script_id(key: str, name: str) -> int:
    req = urllib.request.Request(f"{API}/compute/script?page=1&perPage=1000", headers={"AccessKey": key})
    items = json.load(urllib.request.urlopen(req))["Items"]
    ids = [s["Id"] for s in items if s["Name"] == name and not s.get("Deleted")]
    if len(ids) != 1:
        sys.exit(f"edge-logs: expected exactly one script named {name!r}, found {len(ids)}")
    return ids[0]


def render(record: dict, raw: bool) -> str:
    if raw:
        return json.dumps(record)
    inner = json.loads(record["log"])
    at = datetime.datetime.fromtimestamp(record["timestamp"] / 1000, datetime.UTC)
    zone = record.get("labels", {}).get("ServerZone", "")
    return f"{at:%Y-%m-%d %H:%M:%S} {zone:<3} {inner['level']:<5} v{inner['script_version']} {inner['message'].rstrip()}"


async def pull(url: str, key: str, follow: bool, raw: bool) -> int:
    async with websockets.connect(url, max_size=None, additional_headers={"AccessKey": key}) as ws:
        # The replay arrives newest-first and unsorted against live lines, so it is buffered and sorted.
        history = []
        try:
            while True:
                history.append(json.loads(await asyncio.wait_for(ws.recv(), QUIET)))
        except asyncio.TimeoutError:
            pass
        for record in sorted(history, key=lambda r: r["timestamp"]):
            print(render(record, raw), flush=True)
        print(f"edge-logs: {len(history)} retained line(s)", file=sys.stderr)
        if follow:
            print("edge-logs: following — ^C to stop", file=sys.stderr)
            async for message in ws:
                print(render(json.loads(message), raw), flush=True)
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--script", default=SCRIPT_NAME, help=f"edge script name (default {SCRIPT_NAME})")
    p.add_argument("--level", help="Info or Error")
    p.add_argument("--zone", help="bunny edge location, e.g. SG, TX, DE")
    p.add_argument("--follow", "-f", action="store_true", help="keep streaming new lines")
    p.add_argument("--json", action="store_true", help="print the raw records")
    args = p.parse_args()

    key = os.environ.get("BUNNY_API_KEY") or sys.exit("edge-logs: BUNNY_API_KEY unset — run under secrets-env")
    query = "history=100" + (f"&Level={args.level}" if args.level else "") + (f"&ServerZone={args.zone}" if args.zone else "")
    url = f"{LOGS}/{script_id(key, args.script)}?{query}"
    try:
        return asyncio.run(pull(url, key, args.follow, args.json))
    except KeyboardInterrupt:
        return 0
    except websockets.InvalidStatus as e:
        sys.exit(f"edge-logs: the log endpoint refused: HTTP {e.response.status_code}")


if __name__ == "__main__":
    sys.exit(main())

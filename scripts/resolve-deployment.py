#!/usr/bin/env python3
"""Resolve a deployment and emit every rendering (capability `deployment-configuration`).

WHY THIS EXISTS. A deployment fact — the device-facing domain, the storage zone, the Apple team and
bundle ids — is read by FOUR toolchains: Deno (`api/`), Gradle (`domain/`), Xcode (`iosApp/`) and
Astro (`site/`), plus the App Store metadata. Before this, each held its own copy: the domain lived in
NINE places, six of them pinned by nothing at all, and `TEAM_ID`/`BUNDLE_ID` were written twice in two
languages with nothing checking they agreed — while composing the App Attest `rpIdHash` and the AASA
`appIDs`, where drift fails every attestation and stops every universal link matching, SILENTLY.

Guards did not scale because guards are opt-in: they cover what someone remembered, which is exactly how
`BACKGROUND_UPLOAD_URL_BASE` — the device-facing upload host itself — went unpinned. Generation is total.

WHY PYTHON. No CI job carries both Deno and Gradle (`build.yml` installs Java only; `api-deploy.yml`
installs Deno only), so neither runtime can be the single resolver without forcing a toolchain into a
workflow that deliberately lacks it. Stdlib-only Python3 is present on `ubuntu-latest`, on `macos-26`
(`ios.yml` already runs it there with no setup step) and on every dev machine. Stdlib-only also sidesteps
the PEP-668 externally-managed caveat `ios.yml` records, which concerns `pip install`, not imports.

WHY ONE INVOCATION EMITS EVERYTHING. A per-rendering mode would let one artifact be rendered from `prod`
while another was rendered from `local` — artifacts that DISAGREE, which is the bug class this exists to
remove. Emitting every rendering from one resolution makes that unrepresentable, and gives "has the
resolver run?" a single answer for the whole repository.

Usage:  scripts/resolve-deployment.py <deployment>            # e.g. prod, local
        scripts/resolve-deployment.py <deployment> --root <repo-root>

Exits non-zero, naming the file and the key, on any validation failure — and writes NOTHING when it does,
so a partially-updated set of artifacts cannot exist.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys

# ── The key inventory: the contract of record ──────────────────────────────────────────────────────
#
# For every key: which RENDERINGS it appears in, its SCOPE, when it is REQUIRED, its DEFAULT where
# absence has a defined meaning, and its RATIONALE. This is where the load-bearing comments that used to
# live in `api/src/config.ts` belong now, because the values are consumed by several toolchains and a
# comment in one of them is invisible to the others.
#
# The RENDERING SET is the whole containment guarantee. There is deliberately NO secret/non-secret
# classification: a key reaches exactly the artifacts named here and no others, which is checkable
# without anyone having to classify anything. The Sentry DSN forced this — it is a write-only ingestion
# key, already extractable from any shipped IPA, and deliberately baked into Release archives, so
# "build scope never carries a secret" was already false.
#
# The rendering set bounds where a value APPEARS. It does not assert the value SURVIVES the trip — see
# RAW below, which is the rule that keeps those two apart.

JSON = "json"  # api/src/deployment.ts — the Deno bundle
PROPS = "properties"  # build/deployment.properties — Gradle
XCCONFIG = "xcconfig"  # iosApp/Configuration/Deployment.xcconfig — Xcode BUILD SETTINGS only
PLIST = "plist"  # iosApp/Configuration/Deployment.plist — bundled into app + extension
METADATA = "metadata"  # build/metadata/** — the App Store listing
SITE = "site"  # site/src/deployment.json

BAKED = {PROPS, XCCONFIG, PLIST, METADATA, SITE}
"""Renderings with no run time in which to resolve an environment reference.

A `.properties` file, an `.xcconfig`, a bundled `.plist`, the App Store listing and a statically-built
site are all consumed
after the process that could have read an environment variable has gone. A runtime-scope key naming one
of these could not be honoured, so the inventory rejects it rather than emitting a name nothing resolves.
"""


RAW = {XCCONFIG, PROPS, METADATA}
"""Renderings whose values are INTERPOLATED, with no escaping layer between value and file.

These may carry only values sourced from LITERALS in authored files, never from the environment. The
distinction is REVIEWABILITY, not secrecy: a literal was read by a human in a pull request, who could
have seen a character the grammar reinterprets. Nobody ever sees an environment value in the context of
the file it lands in, so it must be rendered into a grammar that ESCAPES ([JSON], [SITE], [PLIST]).

This is not hypothetical. `//` opens a comment ANYWHERE on an `.xcconfig` line, and `sentryDsn` — the one
environment-sourced value ever written verbatim into that grammar — was truncated to `https:`, which is
non-empty, so the SDK failed to start while the in-app bug-report dialog kept opening and losing every
dump. Four TestFlight builds shipped mute. The fix was not a better escape; it was moving the value into
a grammar that escapes, leaving this rendering carrying only reviewed literals.
"""


class Key:
    def __init__(self, name, renderings, *, scope="runtime", required="always", default=None,
                 env_ref=False, doc=""):
        self.name = name
        self.renderings = set(renderings)
        self.scope = scope
        self.required = required
        self.default = default
        # A key that MUST be an environment reference, never a literal. This is what makes
        # "no secret value appears in any authored file" a shape the schema cannot express
        # rather than a convention someone has to uphold.
        self.env_ref = env_ref
        self.doc = doc.strip()


INVENTORY = [
    Key("domain", [JSON, XCCONFIG, PLIST, PROPS, METADATA, SITE], doc="""
        The device-facing origin, and the host the AASA is served for. ONE value behind the app's
        LINK_ORIGIN, the `applinks:` entitlement, the compile-time upload host, the served AASA, the
        site's canonical URLs and the App Store listing URLs. It MUST be a domain we control: swapping
        the backend runtime is then a DNS repoint rather than a forced rebuild, which is what let the
        previous runtime be retired without a TestFlight round.

        Reaches the xcconfig as `ASSOCIATED_DOMAIN` (an entitlement substitution, which can only read a
        build setting) and the plist as `uploadBase`. The upload base carries a scheme, so it must be
        rendered where `//` is data rather than a comment.

        `uploadBase` is COMPILE-TIME and cannot be otherwise: the background-upload subsystem validates
        every job's destination against the extension bundle's baked value, so a runtime-configurable
        host is impossible with that API. Both bundles carry it — the app itself never uploads, the
        extension does, and each reads its own bundle. It MUST be HTTPS: default ATS applies and no
        `NSAllowsLocalNetworking` exception ships, so a baked `http://` network host fails silently on
        device (loopback is exempt, which is how a simulator reaches `deno task dev:local`).
    """),
    Key("appName", [XCCONFIG], doc="Product name, Xcode only."),
    Key("bundleId", [JSON, XCCONFIG], doc="""
        The app's bundle id. Doubles as the APNs topic, and composes the App Attest app id with the team
        id — both DERIVED at their use sites rather than restated, so they cannot drift from it.
    """),
    Key("teamId", [JSON, XCCONFIG], doc="""
        Apple Team ID. Drives DEVELOPMENT_TEAM, the provider-JWT `iss`, and the App Attest app id's first
        component. Not a secret (visible in any shipped IPA).
    """),
    Key("apnsKeyId", [JSON], doc="APNs Auth Key id — the provider-JWT `kid`."),
    Key("appStoreUrl", [JSON], doc="Where GET /join sends someone who opened an event link with no app."),
    Key("appAttestRootCa", [JSON], doc="""
        Apple's App Attest ROOT CA — the trust anchor every attestation chain is verified against. A
        public fact (Apple publishes it), so declaring it exposes nothing, and shipping it in the same
        artifact as the code that reads it means a verification change cannot deploy without its anchor.
    """),
    Key("eventCapacity", [JSON], doc="""
        Maximum devices EVER enrolled per event (active OR departed — leaving frees no slot). PRODUCT
        POLICY, not a deployment-varying fact: every deployment extends the same component. The only
        future paid-tier lever.
    """),
    Key("eventWindowMaxSeconds", [JSON], doc="""
        Largest permitted `endsAt - startsAt`, and the absent-`endsAt` fallback. DELIBERATELY DISTINCT
        from the lifetime even while they hold the same number: they answer different questions ("how
        long may photos be TAKEN for?" vs "how long do we KEEP them?"), only the lifetime is stamped, and
        collapsing them would make a future divergence a silent behaviour change in two places.
    """),
    Key("eventLifetimeSeconds", [JSON], doc="""
        How long an event's data is kept, measured from max(createdAt, startsAt). Stamped onto the marker
        as a DURATION, never an absolute delete-by instant, so a change here cannot alter a live event.
    """),
    Key("attestTokenTtlSeconds", [JSON], doc="""
        Device-token lifetime. A MARGIN, not a security knob: the extension cannot renew (App Attest is
        unavailable in an app extension), and the silent push that most reliably wakes the app is
        triggered by a SUCCESSFUL upload — so an expired token deadlocks its own renewal until the user
        opens the app. It is also the only bound on a token lifted from a backup. DO NOT LENGTHEN.
    """),
    Key("storage.kind", [JSON], doc="""
        Sealed discriminator over how this deployment reaches storage. `bunny` is the deployed runtime;
        `filesystem` is the local rig, which has no filesystem in an Edge Script — so a filesystem
        deployment CANNOT boot there, and "right commit, wrong deployment" fails closed rather than
        serving wrongly.
    """),
    Key("storage.zone", [JSON], required="kind==bunny", doc="""
        bunny Storage zone name. Doubles as the S3 Access Key ID and the bucket when presigning.
    """),
    Key("storage.host", [JSON], required="kind==bunny", doc="""
        bunny native Storage host. MUST be the zone's MAIN region host, never a replica: main-region reads
        are read-after-write consistent, so the nightly sweep sees a concurrent rejoin's fresh manifest
        and cannot delete an event out from under an active device. A stale replica read is the one
        failure mode that would delete live data.
    """),
    Key("storage.s3Region", [JSON], required="kind==bunny", doc="""
        S3 region of the storage zone, used only to presign download URLs. The S3 HOST is DERIVED from it
        at the use site (`<region>-s3.storage.bunnycdn.com`) rather than stated, so the two cannot
        disagree — they previously did restate one fact as two constants.
    """),
    Key("storage.root", [JSON], required="kind==filesystem", doc="""
        Directory the local rig's filesystem shim reads and writes. Never reaches a deployed bundle.
    """),
    Key("storage.accessKey", [JSON], required="kind==bunny", env_ref=True, doc="""
        Storage-zone password. Sent as the native `AccessKey` header, and doubled as the S3 SECRET when
        presigning. A genuine credential: the deployment names the variable, never the value.
    """),
    Key("apnsPrivateKey", [JSON], required="kind==bunny", env_ref=True, doc="""
        The APNs Auth Key `.p8` PEM CONTENTS (not a path). Do NOT trim it — a PEM's trailing newline is
        significant to parsers; reject it only when absent or whitespace-only.
    """),
    Key("attestTokenKey", [JSON], required="kind==bunny", env_ref=True, doc="""
        Signs and verifies the device bearer token. MUST be set on the Edge Script BEFORE the code reading
        it is merged: CI ships code but cannot ship platform config, so merging first takes the backend
        down until it is set by hand. That is not hypothetical — it is how this backend stayed dead for
        two weeks.
    """),
    Key("databaseUrl", [JSON], required="kind==bunny", env_ref=True, doc="""
        libSQL/HTTP URL of this deployment's relational store (capability `database`). An environment
        reference, like every credential: it addresses a live store holding real events. EACH DEPLOYMENT
        ADDRESSES ITS OWN — a dev run that wrote or deleted rows in the production store would corrupt
        live events, and unlike the storage zone there is no per-object blast radius to fall back on.
        MUST be set on the Edge Script BEFORE the code reading it is merged, for the same reason
        `attestTokenKey` must.
    """),
    Key("databaseToken", [JSON], required="kind==bunny", env_ref=True, doc="""
        Access token for {@link databaseUrl}. A genuine credential: the deployment names the variable,
        never the value.
    """),
    Key("sha", [JSON], scope="build", default="dev", doc="""
        The commit this artifact was built from, served by the health route so a post-deploy probe can
        tell THIS bundle from the previous one still being served. `deno bundle` offers no build-time
        substitution (no --define; denoland/deno#35347), so a generated module is the only mechanism.
        Absent means "not a CI build": the probe treats `dev` as a terminal failure, never a retry.
    """),
    Key("channel", [XCCONFIG, PLIST], scope="build", default="dev", doc="""
        Whether this build is DISTRIBUTED. One discriminator, from which the renderers derive
        APS_ENVIRONMENT (the entitlement, xcconfig) and `apnsEnv` / `sentryEnvironment` (the plist) —
        settings that must agree and were once held together only by a comment. Deriving them makes
        disagreement unrepresentable. It also gates the DSN: absence is the off-switch.

        `apnsEnv` is reported by the app in `devices/<id>/config.json`, so the backend picks the right
        APNs host per token (capability `push-registration`); it is kept in lockstep with the
        `aps-environment` entitlement by being derived from this same value rather than stated twice.

        This key is environment-sourced and names a RAW rendering, which the rule above forbids for a
        VALUE — and it is not one: `channel` never appears in the xcconfig, only the enums derived from
        it do, and those are literals this file chooses. The rendering set records where a key
        INFLUENCES an artifact as well as where it appears; the two coincide for every other key.
    """),
    Key("sentryDsn", [JSON, PLIST], scope="build", default="", doc="""
        Crash-reporting ingestion key. NOT a secret in the disclosure sense — it authorises sending only,
        and ships inside every IPA — but not committed either, because a public repo would expose the
        instance to quota abuse. ABSENCE IS THE OFF-SWITCH: the renderer emits nothing unless `channel`
        names a distributed build, so a stray export cannot arm reporting on a dev build.
        (The api bundle carries it unread; backend crash reporting is a separate change.)

        It renders to the PLIST and never to the xcconfig. A DSN contains `//`, which opens a comment in
        that grammar — see RAW above. This is the value that proved the rule.

        Read by the `CrashReporting` adapter in BOTH processes, each from its own bundle, alongside
        `sentryEnvironment`. Absent in every dev/sideload build; only a CI Release archive resolves it
        (capability `ios-testflight-delivery`). It cannot be injected on an `xcodebuild` line any more —
        a build-setting override cannot substitute into a generated resource — so an on-device build
        that reports is a `workflow_dispatch` of `ios.yml`, not a sideload.
    """),
]

BY_NAME = {k.name: k for k in INVENTORY}
STORAGE_KINDS = {"bunny", "filesystem"}


class ResolveError(Exception):
    pass


def fail(msg: str) -> None:
    raise ResolveError(msg)


# ── Loading and merging ────────────────────────────────────────────────────────────────────────────


def load_json(path: pathlib.Path, what: str) -> dict:
    if not path.is_file():
        fail(f"{what} not found: {path}")
    try:
        value = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        fail(f"{path}: invalid JSON — {e}")
    if not isinstance(value, dict):
        fail(f"{path}: expected a JSON object")
    return value


def merge(deployments: pathlib.Path, name: str) -> dict:
    """Shallow merge of top-level keys, in `extends` order, the deployment's own keys last.

    Deliberately too weak to grow a templating language: no nesting, no deep merge, no interpolation,
    no conditionals. Anything it cannot express is a signal to restructure the data. Deep merge is
    excluded specifically because it is the point at which a resolved value can no longer be predicted
    by reading one file — the wrong trade for configuration that decides which bucket holds photos.
    """
    path = deployments / f"{name}.json"
    if not path.is_file():
        fail(f"unknown deployment '{name}' — no such file: {path}")
    own = load_json(path, "deployment")

    resolved: dict = {}
    for rel in own.pop("extends", []):
        component = deployments / rel
        values = load_json(component, f"component '{rel}' referenced by {path.name}")
        if "extends" in values:
            fail(f"{component}: a component may not itself declare `extends`")
        resolved.update(values)
    resolved.update(own)
    return resolved


# ── Validation ─────────────────────────────────────────────────────────────────────────────────────


def flatten(resolved: dict) -> dict:
    """`{"storage": {"kind": …}}` → `{"storage.kind": …}`, so the inventory is one flat namespace."""
    flat = {}
    for key, value in resolved.items():
        if key == "storage":
            if not isinstance(value, dict):
                fail("`storage` must be an object")
            for sub, sub_value in value.items():
                flat[f"storage.{sub}"] = sub_value
        else:
            flat[key] = value
    return flat


def is_env_ref(value) -> bool:
    return isinstance(value, dict) and "env" in value


def validate(flat: dict, name: str) -> str:
    """Returns the storage kind. Raises naming the offending key on any failure."""
    for key in flat:
        if key not in BY_NAME:
            fail(f"deployment '{name}': unknown key '{key}' — not declared in the inventory")

    kind = flat.get("storage.kind")
    if kind is None:
        fail(f"deployment '{name}': required key 'storage.kind' is absent")
    if kind not in STORAGE_KINDS:
        fail(
            f"deployment '{name}': storage.kind '{kind}' is outside the sealed set "
            f"{sorted(STORAGE_KINDS)}"
        )

    for key in INVENTORY:
        required = key.required == "always" or key.required == f"kind=={kind}"
        present = key.name in flat
        if required and not present and key.default is None:
            fail(f"deployment '{name}': required key '{key.name}' is absent (storage.kind={kind})")
        if not present:
            continue
        value = flat[key.name]
        if key.env_ref and not is_env_ref(value):
            fail(
                f"deployment '{name}': key '{key.name}' must be an environment reference "
                f'{{"env": "NAME"}}, never a literal — a credential value may not appear in a file'
            )
        if is_env_ref(value):
            scope = value.get("scope", "runtime")
            if scope not in ("build", "runtime"):
                fail(f"deployment '{name}': key '{key.name}' declares unknown scope '{scope}'")
            if scope != key.scope:
                fail(
                    f"deployment '{name}': key '{key.name}' declares scope '{scope}' but the inventory "
                    f"declares '{key.scope}'"
                )
            if scope == "runtime" and key.renderings & BAKED:
                fail(
                    f"deployment '{name}': key '{key.name}' is runtime-scope but appears in baked "
                    f"rendering(s) {sorted(key.renderings & BAKED)}, which have no run time to resolve it"
                )
        elif key.scope == "build" and key.name in flat:
            # A literal for a build-scope key is fine; it is simply not read from the environment.
            pass
    return kind


# ── Resolution of values ───────────────────────────────────────────────────────────────────────────


def realise(flat: dict, env: dict) -> dict:
    """Resolve BUILD-scope references now; copy RUNTIME-scope references through verbatim.

    The resolver reads the environment ONLY for build scope. A runtime reference must reach the artifact
    as a NAME, never a value: resolving one here would bake a live secret into `dist/main.js` and would
    require CI to hold runtime secrets it is forbidden to hold (bunny issues no scoped API key, so the
    key that could write them owns every user's photos and our DNS).
    """
    out = dict(flat)
    for key in INVENTORY:
        if key.scope != "build":
            continue
        value = out.get(key.name)
        if is_env_ref(value):
            out[key.name] = env.get(value["env"], key.default)
        elif value is None and key.default is not None:
            out[key.name] = key.default
    return out


def nest(flat: dict) -> dict:
    out: dict = {}
    for key, value in flat.items():
        if key.startswith("storage."):
            out.setdefault("storage", {})[key[len("storage."):]] = value
        else:
            out[key] = value
    return out


def project(flat: dict, rendering: str) -> dict:
    return {k: v for k, v in flat.items() if rendering in BY_NAME[k].renderings}


# ── Renderings ─────────────────────────────────────────────────────────────────────────────────────
#
# Renderers MAY derive, deterministically and in one place. Composition may not. Where several outputs
# must agree, they are derived from ONE resolved value so no combination exists in which they disagree.


def render_deployment_ts(flat: dict, sha) -> str:
    """One generated TypeScript module: the union type AND the typed value.

    A `.d.ts` beside a `.json` would not type a JSON import — Deno infers the type from the JSON itself,
    so applying the union would need an unchecked `as` assertion. Emitting a typed `const` instead makes
    the COMPILER check the emitted data against the emitted type, so a renderer bug (a number where the
    type says string, a branch missing a field) is a type error rather than a runtime surprise.

    `sha` is build IDENTITY, not configuration: it sits beside `config`, never inside it, so it reaches
    the app through `Deps` (where `now` already lives) rather than through `Config`.
    """
    body = nest(project(flat, JSON))
    body.pop("sha", None)
    return "\n".join([
        render_types(flat),
        "export const deployment: { readonly config: ResolvedDeployment; readonly sha: string } = {",
        f"  config: {json.dumps(body, indent=2, sort_keys=True)},",
        f"  sha: {json.dumps(sha)},",
        "};",
        "",
        "export default deployment;",
        "",
    ])


def render_site(flat: dict) -> str:
    return json.dumps(nest(project(flat, SITE)), indent=2, sort_keys=True) + "\n"


def render_properties(flat: dict) -> str:
    lines = ["# GENERATED by scripts/resolve-deployment.py — do not edit, do not commit."]
    for name, value in sorted(project(flat, PROPS).items()):
        lines.append(f"{name.replace('.', '_')}={value}")
    return "\n".join(lines) + "\n"


def render_xcconfig(flat: dict) -> str:
    """Xcode BUILD SETTINGS and entitlement substitutions ONLY — see [RAW].

    Everything the device READS at runtime lives in [render_plist] instead. What is left here is what
    has nowhere else to go: `PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER` and `DEVELOPMENT_TEAM` are
    build settings, and an entitlement's `$(…)` substitution can only read a build setting.

    Every value below is therefore a LITERAL from an authored file, or an enum this function chooses.
    That is what removes the comment hazard structurally: `//` still opens a comment here, and there is
    still no escape for it, but no value that could contain one can reach this rendering any more. The
    `$()` guard the upload base used to carry is deliberately GONE rather than generalised — a per-site
    escape is what failed, since it covers what someone remembered.
    """
    p = project(flat, XCCONFIG)
    distributed = p.get("channel") == "release"
    aps = "production" if distributed else "development"
    return "\n".join([
        "// GENERATED by scripts/resolve-deployment.py — do not edit, do not commit.",
        "// Included by Config.xcconfig. Every value here derives from the resolved deployment.",
        "// Build settings and entitlement inputs only — the values the app READS are in Deployment.plist.",
        f"APP_NAME = {p['appName']}",
        f"BUNDLE_ID = {p['bundleId']}",
        f"TEAM_ID = {p['teamId']}",
        f"ASSOCIATED_DOMAIN = applinks:{p['domain']}",
        f"APS_ENVIRONMENT = {aps}",
        "",
    ])


def render_plist(flat: dict) -> str:
    """The values BOTH iOS processes read from their own bundle (capability `deployment-configuration`).

    Keyed by the inventory's own names, so this artifact is a direct projection of the inventory as the
    JSON and site renderings already are — the `SCREAMING_SNAKE` names it replaces were xcconfig build
    settings, and carrying them here would preserve the shape of the thing being removed.

    A property list ESCAPES, which is the whole reason these four values live here: `uploadBase` and
    `sentryDsn` both carry `//`, which the xcconfig grammar reads as a comment (see [RAW]).

    `apnsEnv` and `sentryEnvironment` are DERIVED from the same `channel` discriminant that gives the
    entitlement its `APS_ENVIRONMENT`, so the three cannot disagree. `sentryDsn` is emitted only for a
    distributed build — absence is the off-switch, enforced here rather than by CI declining to export
    the secret.

    The file is copied into the app bundle AND the extension bundle: each process reads its own
    `NSBundle.mainBundle`, so a value present in one and absent from the other is a reachable state,
    and it is the one `ios.yml` asserts against both bundles.
    """
    p = project(flat, PLIST)
    distributed = p.get("channel") == "release"
    values = {
        "uploadBase": f"{upload_scheme(p['domain'])}://{p['domain']}/api/v1",
        "apnsEnv": "production" if distributed else "sandbox",
        "sentryEnvironment": "production" if distributed else "development",
    }
    if distributed and p.get("sentryDsn"):
        values["sentryDsn"] = p["sentryDsn"]
    rows = "\n".join(
        f"\t<key>{k}</key>\n\t<string>{xml_escape(v)}</string>" for k, v in sorted(values.items())
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0">\n'
        "<dict>\n"
        "\t<!-- GENERATED by scripts/resolve-deployment.py — do not edit, do not commit. -->\n"
        f"{rows}\n"
        "</dict>\n"
        "</plist>\n"
    )


def upload_scheme(domain: str) -> str:
    """`http` for a LOOPBACK literal, `https` for everything else.

    Not a preference — a platform constraint, derived rather than declared so the two cannot disagree.
    Default ATS applies to the app and the extension and NO `NSAppTransportSecurity` exception ships, so
    a plaintext host reached over the network fails SILENTLY on device. ATS exempts the loopback IP
    LITERAL, and only that: measured 2026-08-09 from a real bundle on an iOS 26.5 simulator, a build
    baked with `http://127.0.0.1:8080/api/v1` reached the local rig and got a 201.

    `localhost` is deliberately NOT loopback here. It is a NAME, resolved through DNS, and ATS's
    exemption is documented for the address literal — treating it as exempt would produce a build that
    fails the way this function exists to prevent. An IPv6 literal is read from its BRACKETED form, the
    only form valid in a URL host; splitting a bare `::1` on its first colon yields `""` and would
    silently classify it as non-loopback.

    This is what lets the local rig be SELECTED rather than overridden: an operator points a build at
    `deno task dev:local` by editing `deployments/local.json` and re-running the resolver, with no
    `BACKGROUND_UPLOAD_URL_BASE=` on the xcodebuild line — which could not reach the plist anyway. A
    cloudflared tunnel is not loopback and correctly stays `https`.
    """
    host = domain[1:domain.index("]")] if domain.startswith("[") and "]" in domain \
        else domain.split(":", 1)[0]
    return "http" if host in ("127.0.0.1", "::1") else "https"


def xml_escape(value: str) -> str:
    """The escaping that makes this grammar safe for a value nobody reviewed — the point of [RAW]."""
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def render_types(flat: dict) -> str:
    """Emitted from the INVENTORY, not inferred from the resolved file.

    Inference would reflect whichever deployment happened to be on disk — under `prod` the type would be
    the bunny shape, the sealed union would mean nothing to the compiler, and the rig's code would be
    type-checked against a shape it never sees. Emitting the union forces narrowing on `kind`.
    """
    def ts(key: Key, name: str) -> str:
        sample = flat.get(key.name)
        if key.env_ref:
            # A runtime reference reaches the artifact as a NAME; the reading program resolves it.
            t = "{ readonly env: string }"
        elif isinstance(sample, (int, float)) and not isinstance(sample, bool):
            t = "number"
        else:
            t = "string"
        doc = " ".join(key.doc.split())
        return f"  /** {doc} */\n  readonly {name}: {t};"

    # `sha` is build identity, emitted BESIDE `config` rather than inside it, so it is not a member of
    # ResolvedDeployment even though it reaches the same artifact.
    common = [k for k in INVENTORY if JSON in k.renderings
              and not k.name.startswith("storage.") and k.name != "sha" and k.required == "always"]
    only = {
        kind: [k for k in INVENTORY if JSON in k.renderings
               and not k.name.startswith("storage.") and k.required == f"kind=={kind}"]
        for kind in sorted(STORAGE_KINDS)
    }
    store = {
        kind: [k for k in INVENTORY if k.name.startswith("storage.")
               and k.required in ("always", f"kind=={kind}")]
        for kind in sorted(STORAGE_KINDS)
    }

    def branch(keys, kind):
        rows = []
        for k in keys:
            short = k.name[len("storage."):]
            rows.append(f'  readonly kind: "{kind}";' if short == "kind" else ts(k, short))
        return "{\n" + "\n".join(rows) + "\n}"

    def deployment_branch(kind):
        rows = [ts(k, k.name) for k in only[kind]]
        rows.append(f"  readonly storage: {kind.capitalize()}Storage;")
        return "{\n" + "\n".join(rows) + "\n}"

    return "\n".join([
        "// GENERATED by scripts/resolve-deployment.py — do not edit, do not commit.",
        "//",
        "// The union is over the RESOLVED DEPLOYMENT, not the app-facing Config: `readConfig` narrows to",
        "// the bunny branch or throws (so a filesystem deployment cannot boot on the Edge Script), and the",
        "// local rig narrows to the filesystem branch and synthesises the Config its fetch shim needs.",
        "// Narrow with the exported type guards — a nested discriminant does not narrow the outer type.",
        "",
        f"export type BunnyStorage = {branch(store['bunny'], 'bunny')};",
        "",
        f"export type FilesystemStorage = {branch(store['filesystem'], 'filesystem')};",
        "",
        "export type CommonDeployment = {",
        *[ts(k, k.name) for k in common],
        "};",
        "",
        f"export type BunnyDeployment = CommonDeployment & {deployment_branch('bunny')};",
        "",
        f"export type FilesystemDeployment = CommonDeployment & {deployment_branch('filesystem')};",
        "",
        "export type ResolvedDeployment = BunnyDeployment | FilesystemDeployment;",
        "",
        "export function isBunnyDeployment(d: ResolvedDeployment): d is BunnyDeployment {",
        '  return d.storage.kind === "bunny";',
        "}",
        "",
        "export function isFilesystemDeployment(d: ResolvedDeployment): d is FilesystemDeployment {",
        '  return d.storage.kind === "filesystem";',
        "}",
        "",
    ])


def render_metadata(flat: dict, root: pathlib.Path, out_dir: pathlib.Path) -> list[pathlib.Path]:
    """Substitute the domain placeholder into the committed listing templates.

    Only the domain-derived URL fields carry a placeholder; the listing COPY is never templated, so
    editing App Store text never requires running a generator.
    """
    written = []
    src = root / "metadata"
    if not src.is_dir():
        return written
    domain = flat["domain"]
    for path in sorted(src.rglob("*.json")):
        text = path.read_text()
        if "{{domain}}" not in text:
            continue
        target = out_dir / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text.replace("{{domain}}", domain))
        written.append(target)
    return written


# ── Entry point ────────────────────────────────────────────────────────────────────────────────────


def resolve(root: pathlib.Path, name: str, env: dict) -> dict:
    flat = flatten(merge(root / "deployments", name))
    validate(flat, name)
    return realise(flat, env)


def emit(root: pathlib.Path, flat: dict) -> list[pathlib.Path]:
    """Write every rendering. Called only after validation, so no partial write can occur."""
    sha = flat.get("sha", BY_NAME["sha"].default)
    targets = {
        root / "api/src/deployment.ts": render_deployment_ts(flat, sha),
        root / "build/deployment.properties": render_properties(flat),
        root / "iosApp/Configuration/Deployment.xcconfig": render_xcconfig(flat),
        root / "iosApp/Configuration/Deployment.plist": render_plist(flat),
        root / "site/src/deployment.json": render_site(flat),
    }
    written = []
    for path, body in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        written.append(path)
    written += render_metadata(flat, root, root / "build/metadata")
    return written


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Resolve a deployment and emit every rendering.")
    parser.add_argument("deployment", help="deployment name, e.g. prod or local (no default)")
    parser.add_argument("--root", default=None, help="repository root (default: this script's parent)")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root) if args.root else pathlib.Path(__file__).resolve().parent.parent
    try:
        flat = resolve(root, args.deployment, os.environ)
        written = emit(root, flat)
    except ResolveError as e:
        print(f"resolve-deployment: {e}", file=sys.stderr)
        return 1
    if not args.quiet:
        for path in written:
            print(f"resolve-deployment: wrote {path.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

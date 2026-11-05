#!/usr/bin/env python3
"""Fail closed on inherited release identity and unevidenced beta distribution."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "shell_ui", "sms_carrier", "mms_carrier", "local_model", "memory",
    "calendar", "phone_manual", "backup_restore", "accessibility", "privacy",
)
ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_source(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    def read(relative: str) -> str:
        try:
            return (root / relative).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            errors.append(f"missing or unreadable {relative}")
            return ""

    properties = read("gradle.properties")
    if not re.search(r"(?m)^APP_ID=app\.inkpilot\.android\s*$", properties):
        errors.append("standalone APP_ID is missing")
    appfile = read("fastlane/Appfile")
    if re.findall(r'(?m)^package_name\("([^"]+)"\)', appfile) != ["app.inkpilot.android"]:
        errors.append("Fastlane targets a different package")
    if "json_key_file" in appfile:
        errors.append("Fastlane still references a repository credential file")
    fastfile = read("fastlane/Fastfile")
    if re.search(r"\b(deploy_android|metadata_android|lane\(:deploy\)|lane\(:metadata\))", fastfile):
        errors.append("inherited Fastlane publication lane remains active")

    workflows = list((root / ".github/workflows").glob("*.yml"))
    if not workflows:
        errors.append("release workflows are missing")
    for path in workflows:
        body = read(str(path.relative_to(root)))
        if "FossifyOrg/.github" in body or re.search(r"secrets:\s*inherit", body):
            errors.append(f"upstream reusable workflow or inherited secrets: {path.name}")
    release = read(".github/workflows/release.yml")
    if "workflow_dispatch:" not in release or re.search(r"(?m)^\s{0,2}(push|repository_dispatch|schedule):", release):
        errors.append("release must require an explicit manual workflow dispatch")
    if not all(gate in release for gate in (
        "verify_app_identity.py", "verify_inkpilot_release.py --beta",
        ":agent-core:test", ":app:testFossDebugUnitTest", ":app:lintFossRelease",
        ":app:assembleFossRelease", "candidate_run_id", "actions/download-artifact@",
        "inkPilot-foss-debug", "git diff --name-only", "git merge-base --is-ancestor",
        "gh api", "check_candidate_run.py", "fetch-depth: 0",
        "--candidate-run-id", "--apksigner", "--aapt",
    )):
        errors.append("release lacks identity, tests, provenance, signed beta evidence or minified build gate")
    if re.search(r"\bsecrets[.:]|GPLAY", release):
        errors.append("internal beta qualification must not access publishing credentials")
    build = read(".github/workflows/inkpilot-build.yml")
    if not all(contract in build for contract in (
        "workflow_dispatch:", "name: inkPilot-foss-debug",
        "app/build/outputs/apk/foss/debug/*.apk", "verify_app_identity.py",
        "verify_inkpilot_release.py --source", ":agent-core:test", ":app:assembleFossDebug",
    )):
        errors.append("manual build does not upload its tested inkPilot debug APK")
    for match in re.finditer(r"(?m)^\s*-?\s*uses:\s*([^\s#]+)", release):
        value = match.group(1)
        if not value.startswith("./") and not re.fullmatch(r"(?:[\w.-]+/)+[\w.-]+@[0-9a-f]{40}", value):
            errors.append("release action is not pinned by full commit")

    metadata = root / "fastlane/metadata/android"
    if not (metadata / "en-US/title.txt").exists():
        errors.append("inkPilot store listing is missing")
    for path in metadata.rglob("*.txt") if metadata.exists() else ():
        body = read(str(path.relative_to(root)))
        if re.search(r"Fossify|org\.fossify\.messages|without (?:requiring )?an? internet connection|internet (?:is|used) only for MMS", body, re.I):
            errors.append(f"inherited or false store copy: {path.relative_to(metadata)}")
    if (metadata / "en-US/title.txt").exists() and (metadata / "en-US/title.txt").read_text().strip() != "inkPilot":
        errors.append("store title is not inkPilot")

    strings = root / "app/src/main/res/values/strings.xml"
    try:
        default = {e.get("name"): "".join(e.itertext()) for e in ET.parse(strings).getroot().findall("string")}
    except (ET.ParseError, OSError):
        default = {}; errors.append("default strings XML cannot be parsed")
    faq = default.get("faq_1_text", "")
    if not all(term in faq for term in ("MMS", "optional", "off by default", "local")) or re.search(r"internet (?:is|used) only for MMS|does not (?:need|require) (?:an? )?internet", faq, re.I):
        errors.append("network FAQ omits carrier MMS, optional bridge or local-model posture")
    for path in (root / "app/src/main/res").glob("values-*/strings.xml"):
        try:
            if any(e.get("name") == "faq_1_text" for e in ET.parse(path).getroot().findall("string")):
                errors.append(f"unreviewed translated network claim: {path.parent.name}")
        except ET.ParseError:
            errors.append(f"invalid translated strings XML: {path.parent.name}")

    core = read("agent-core/README.md")
    if "Production repository wiring" in core or "on-device inference, Android screens" in core:
        errors.append("core README reports shipped host work as pending")
    architecture = read("docs/ARCHITECTURE.md")
    if "current destructive migration fallback" in architecture or "current sends bypass" in architecture:
        errors.append("architecture still describes removed destructive/invisible paths as current")
    acceptance = read("docs/DEVICE-ACCEPTANCE.md")
    if "Phone opens Android's human dialer" in acceptance or "inherited conversation and settings UI currently" in acceptance:
        errors.append("device acceptance scope describes outdated Phone or shell behavior")
    if not (root / "docs/RELEASE-EVIDENCE.json").exists():
        errors.append("release evidence manifest is missing")
    return errors


def inspect_signed_apk(apk: Path, expected_package: str, expected_cert: str,
                       apksigner: Path | None, aapt: Path | None) -> list[str]:
    errors: list[str] = []
    for tool, args, label in (
        (apksigner, ["verify", "--verbose", "--print-certs", str(apk)], "APK signature"),
        (aapt, ["dump", "badging", str(apk)], "APK package"),
    ):
        if tool is None or not tool.is_file():
            errors.append(f"{label} inspection tool is missing")
            continue
        try:
            result = subprocess.run([str(tool), *args], capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            errors.append(f"{label} inspection failed")
            continue
        if result.returncode != 0:
            errors.append(f"{label} verification failed")
            continue
        if label == "APK signature":
            certs = [value.replace(":", "").lower() for value in re.findall(
                r"(?im)^Signer #\d+ certificate SHA-256 digest: ([0-9a-f:]+)\s*$", result.stdout)]
            if len(certs) != 1 or certs[0] != expected_cert:
                errors.append("APK signing certificate differs from physical evidence")
        else:
            package = re.search(r"(?m)^package: name='([^']+)'", result.stdout)
            if package is None or package.group(1) != expected_package:
                errors.append("APK package differs from inkPilot beta identity")
    return errors


def check_beta(root: Path = ROOT, apk: Path | None = None,
               apksigner: Path | None = None, aapt: Path | None = None,
               candidate_run_id: str | None = None) -> list[str]:
    errors = check_source(root)
    try:
        evidence = json.loads((root / "docs/RELEASE-EVIDENCE.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return errors + ["beta evidence manifest is unreadable"]
    if evidence.get("candidate_kind") != "debug":
        errors.append("internal beta requires the installed debug-signed candidate")
    if not re.fullmatch(r"[0-9a-f]{40}", str(evidence.get("candidate_source_sha", ""))):
        errors.append("candidate source revision is missing")
    run_id = str(evidence.get("candidate_run_id", ""))
    if not run_id.isdigit() or run_id == "0" or run_id != candidate_run_id:
        errors.append("candidate artifact run ID differs from reviewed evidence")
    recorded_hash = evidence.get("apk_sha256", "")
    valid_hash = isinstance(recorded_hash, str) and re.fullmatch(r"[0-9a-f]{64}", recorded_hash)
    expected_cert = evidence.get("signing_cert_sha256", "")
    if not isinstance(expected_cert, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
        errors.append("reviewed APK signing certificate is missing")
        expected_cert = ""
    gates = evidence.get("gates", {})
    for name in REQUIRED:
        item = gates.get(name, {})
        reference = item.get("reference")
        if item.get("status") != "passed" or not isinstance(reference, str) or not reference.strip():
            errors.append(f"beta evidence pending: {name}")
            continue
        if reference != f"docs/release-evidence/{name}.md":
            errors.append(f"beta evidence path is not gate-specific: {name}")
            continue
        proof = root / reference
        if not proof.is_file():
            errors.append(f"beta evidence file missing: {name}")
            continue
        match = re.search(r"(?m)^APK SHA-256: ([0-9a-f]{64})$", proof.read_text(encoding="utf-8"))
        if not valid_hash or match is None or match.group(1) != recorded_hash:
            errors.append(f"beta evidence APK hash mismatch: {name}")
    if not valid_hash:
        errors.append("reviewed APK SHA-256 is missing")
    if apk is None:
        errors.append("installed beta APK was not supplied for verification")
    elif not apk.is_file():
        errors.append("installed beta APK is missing")
    else:
        hasher = hashlib.sha256()
        with apk.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(block)
        if hasher.hexdigest() != recorded_hash:
            errors.append("installed beta APK differs from reviewed SHA-256")
        errors.extend(inspect_signed_apk(apk, "app.inkpilot.android.debug", expected_cert, apksigner, aapt))
    manifest = root / "app/src/main/AndroidManifest.xml"
    try:
        application = ET.parse(manifest).getroot().find("application")
        if application is None:
            raise ValueError("application element missing")
        for activity in application.findall("activity"):
            name = activity.get(ANDROID + "name", "")
            if name.startswith(".activities.") and activity.get(ANDROID + "exported") == "true":
                errors.append(f"exported inherited messenger activity remains: {name}")
    except (ET.ParseError, OSError, ValueError):
        errors.append("manifest beta ingress cannot be inspected")
    return errors


def check_release(root: Path = ROOT, apk: Path | None = None,
                  apksigner: Path | None = None, aapt: Path | None = None,
                  candidate_run_id: str | None = None) -> list[str]:
    """Public distribution stays blocked until its own signed artifact and proof path exist."""
    return check_beta(root, apk, apksigner, aapt, candidate_run_id) + [
        "public release signing and physical candidate qualification are not configured"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--beta", action="store_true", help="qualify the exact installed internal debug APK")
    parser.add_argument("--release", action="store_true", help="public distribution remains blocked")
    parser.add_argument("--source", action="store_true", help="check source and release metadata only (default)")
    parser.add_argument("--apk", type=Path, help="downloaded APK installed on the test phone")
    parser.add_argument("--apksigner", type=Path, help="Android SDK apksigner executable")
    parser.add_argument("--aapt", type=Path, help="Android SDK aapt executable")
    parser.add_argument("--candidate-run-id", help="successful manual build artifact run ID")
    args = parser.parse_args()
    if args.beta and args.release:
        parser.error("choose either --beta or --release")
    candidate = (ROOT, args.apk, args.apksigner, args.aapt, args.candidate_run_id)
    failures = check_release(*candidate) if args.release else check_beta(*candidate) if args.beta else check_source()
    for failure in failures:
        print(f"FAIL: {failure}")
    if not failures:
        print("PASS: internal beta candidate and evidence gates" if args.beta else "PASS: inkPilot release source contracts")
    return bool(failures)


if __name__ == "__main__":
    raise SystemExit(main())

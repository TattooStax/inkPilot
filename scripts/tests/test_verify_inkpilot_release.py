"""Contract tests for the source and manually reviewed release gates."""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from verify_inkpilot_release import REQUIRED, check_beta, check_release, check_source  # noqa: E402


class ReleaseVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        required = (
            "gradle.properties", "fastlane/Appfile", "fastlane/Fastfile",
            ".github/workflows/release.yml", ".github/workflows/inkpilot-build.yml",
            ".github/workflows/inkpilot-beta-build.yml", "agent-core/README.md",
            "docs/ARCHITECTURE.md", "docs/DEVICE-ACCEPTANCE.md",
            "docs/RELEASE-EVIDENCE.json", "app/src/main/res/values/strings.xml",
            "app/src/main/AndroidManifest.xml",
        )
        for relative in required:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        shutil.copytree(ROOT / "fastlane/metadata/android/en-US", self.root / "fastlane/metadata/android/en-US")

    def edit(self, relative: str, old: str, new: str):
        path = self.root / relative
        contents = path.read_text()
        assert old in contents, old
        path.write_text(contents.replace(old, new))

    def test_source_contract_starts_clean_and_rejects_inherited_publication(self):
        self.assertEqual([], check_source(self.root))
        self.edit("fastlane/Appfile", "app.inkpilot.android", "org.fossify.messages")
        self.edit(".github/workflows/release.yml", "workflow_dispatch:",
                  "workflow_dispatch:\n  # old upstream\n  # FossifyOrg/.github/.github/workflows/release.yml@main\n  # secrets: inherit")
        self.edit("fastlane/metadata/android/en-US/full_description.txt", "local", "Fossify local")
        self.edit(".github/workflows/release.yml", "actions/download-artifact@", "actions/upload-artifact@")
        self.edit(".github/workflows/inkpilot-build.yml", "name: inkPilot-foss-debug", "name: wrong-app")
        errors = "\n".join(check_source(self.root))
        self.assertIn("Fastlane targets a different package", errors)
        self.assertIn("upstream reusable workflow", errors)
        self.assertIn("inherited or false store copy", errors)
        self.assertIn("release lacks identity, tests, provenance", errors)
        self.assertIn("manual build does not upload", errors)

    def test_signed_builder_requires_manual_run_scoped_secrets_and_identity_check(self):
        self.edit(".github/workflows/inkpilot-beta-build.yml", "  workflow_dispatch:", "  push:")
        self.edit(".github/workflows/inkpilot-beta-build.yml",
                  "secrets.INKPILOT_BETA_KEYSTORE_BASE64", "secrets.WRONG_KEY")
        self.edit(".github/workflows/inkpilot-beta-build.yml",
                  '"app.inkpilot.android"', '"app.inkpilot.android.debug"')
        self.assertIn("signed canonical beta build lacks manual provenance",
                      "\n".join(check_source(self.root)))

    def test_false_faq_and_unreviewed_translation_are_rejected(self):
        self.edit("app/src/main/res/values/strings.xml", "MMS uses carrier mobile data.",
                  "Internet is only for MMS. MMS uses carrier mobile data.")
        locale = self.root / "app/src/main/res/values-de/strings.xml"
        locale.parent.mkdir(parents=True)
        locale.write_text('<resources><string name="faq_1_text">Internet only for MMS</string></resources>')
        errors = "\n".join(check_source(self.root))
        self.assertIn("network FAQ", errors)
        self.assertIn("unreviewed translated network claim: values-de", errors)

    def test_release_requires_reviewed_files_candidate_hash_and_closed_legacy_ingress(self):
        apk = self.root / "candidate.apk"
        apk.write_bytes(b"synthetic unsigned build")
        signer = self.root / "apksigner"
        signer.write_text('#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: ' + 'b' * 64 + '"\n')
        signer.chmod(0o755)
        inspector = self.root / "aapt"
        inspector.write_text("#!/bin/sh\necho \"package: name='app.inkpilot.android.debug' versionCode='1'\"\n")
        inspector.chmod(0o755)
        manifest = self.root / "app/src/main/AndroidManifest.xml"
        tree = ET.parse(manifest)
        android_name = "{http://schemas.android.com/apk/res/android}name"
        android_exported = "{http://schemas.android.com/apk/res/android}exported"
        legacy = next(activity for activity in tree.getroot().find("application").findall("activity")
                      if activity.get(android_name, "").startswith(".activities."))
        legacy.set(android_exported, "true")
        tree.write(manifest)
        errors = "\n".join(check_beta(self.root, apk, signer, inspector, "123"))
        self.assertIn("beta evidence pending: sms_carrier", errors)
        self.assertIn("reviewed APK SHA-256 is missing", errors)
        self.assertIn("exported inherited messenger activity remains", errors)

        evidence = json.loads((self.root / "docs/RELEASE-EVIDENCE.json").read_text())
        evidence["candidate_kind"] = "debug"
        evidence["candidate_source_sha"] = "a" * 40
        evidence["candidate_run_id"] = "123"
        evidence["signing_cert_sha256"] = "b" * 64
        evidence["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        proof_dir = self.root / "docs/release-evidence"
        proof_dir.mkdir()
        for key in REQUIRED:
            (proof_dir / f"{key}.md").write_text(
                f"APK SHA-256: {evidence['apk_sha256']}\nSynthetic test fixture; no carrier claim.\n")
        evidence["gates"] = {key: {"status": "passed", "reference": f"docs/release-evidence/{key}.md"}
                             for key in REQUIRED}
        (self.root / "docs/RELEASE-EVIDENCE.json").write_text(json.dumps(evidence))
        tree = ET.parse(manifest)
        for activity in tree.getroot().find("application").findall("activity"):
            if activity.get(android_name, "").startswith(".activities."):
                activity.set(android_exported, "false")
        tree.write(manifest)
        self.assertEqual([], check_beta(self.root, apk, signer, inspector, "123"))
        self.assertIn("public distribution approval", check_release(self.root, apk, signer, inspector, "123")[-1])
        self.assertIn("candidate artifact run ID differs from reviewed evidence", check_beta(self.root, apk, signer, inspector, "999"))

        # A new canonical package is side by side with the installed debug package.
        evidence["candidate_kind"] = "release"
        (self.root / "docs/RELEASE-EVIDENCE.json").write_text(json.dumps(evidence))
        self.assertIn("canonical beta signing certificate differs from reviewed public pin",
                      check_beta(self.root, apk, signer, inspector, "123"))
        pin = self.root / "docs/BETA-SIGNING-CERT.sha256"
        pin.write_text("c" * 64 + "\n")
        self.assertIn("canonical beta signing certificate differs from reviewed public pin",
                      check_beta(self.root, apk, signer, inspector, "123"))
        pin.write_text("b" * 64 + "\n")
        self.assertIn("APK package differs", "\n".join(check_beta(self.root, apk, signer, inspector, "123")))
        inspector.write_text("#!/bin/sh\necho \"package: name='app.inkpilot.android'\"\n")
        self.assertEqual([], check_beta(self.root, apk, signer, inspector, "123"))
        evidence["candidate_kind"] = ["release"]
        (self.root / "docs/RELEASE-EVIDENCE.json").write_text(json.dumps(evidence))
        self.assertIn("internal beta requires an exact signed", "\n".join(
            check_beta(self.root, apk, signer, inspector, "123")))
        evidence["candidate_kind"] = "debug"
        (self.root / "docs/RELEASE-EVIDENCE.json").write_text(json.dumps(evidence))
        inspector.write_text("#!/bin/sh\necho \"package: name='app.inkpilot.android.debug'\"\n")
        signer.write_text('#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: ' + 'c' * 64 + '"\n')
        self.assertIn("APK signing certificate differs from physical evidence",
            check_beta(self.root, apk, signer, inspector, "123"))
        signer.write_text('#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: ' + 'b' * 64 + '"\n')
        inspector.write_text("#!/bin/sh\necho \"package: name='org.fossify.messages'\"\n")
        self.assertIn("APK package differs", "\n".join(check_beta(self.root, apk, signer, inspector, "123")))
        inspector.write_text("#!/bin/sh\necho \"package: name='app.inkpilot.android.debug'\"\n")
        signer.write_text("#!/bin/sh\nexit 1\n")
        self.assertIn("APK signature verification failed", check_beta(self.root, apk, signer, inspector, "123"))
        apk.write_bytes(b"different candidate")
        self.assertIn("installed beta APK differs from reviewed SHA-256", check_beta(self.root, apk, signer, inspector, "123"))
        (proof_dir / "sms_carrier.md").unlink()
        self.assertIn("beta evidence file missing: sms_carrier", check_beta(self.root, apk, signer, inspector, "123"))


if __name__ == "__main__":
    unittest.main()

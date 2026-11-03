#!/usr/bin/env python3
"""Guard discovery separately from the manifest's SMS receiver declaration."""
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("sms_role", ROOT / "scripts/verify_sms_role_qualification.py")
ROLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ROLE)


class SmsVisibilityTests(unittest.TestCase):
    def check(self, mutate):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        mutate(manifest.getroot())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            manifest.write(path)
            return ROLE.check_manifest(path)

    def test_current_manifest_has_role_and_discovery_surface(self):
        self.assertEqual([], self.check(lambda root: None))

    def test_receiver_action_does_not_replace_visibility_query(self):
        def remove_query(root):
            queries = root.find("queries")
            for intent in list(queries.findall("intent")):
                queries.remove(intent)
        failures = self.check(remove_query)
        self.assertEqual(["no SMS_DELIVER package-visibility query for default-SMS detection"], failures)

    def test_query_must_use_sms_delivery_action(self):
        def wrong_action(root):
            root.find("queries/intent/action").set(ROLE.ANDROID + "name", ROLE.SENDTO)
        self.assertIn("no SMS_DELIVER package-visibility query for default-SMS detection", self.check(wrong_action))


if __name__ == "__main__":
    unittest.main()

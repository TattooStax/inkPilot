#!/usr/bin/env python3
"""Exercise the ratchet CLI against tracked files in isolated Git repos."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).with_name("check-file-size-ratchet.py").resolve()
BASELINE = ".file-size-ratchet.json"


class FileSizeRatchetTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "--quiet")

    def git(self, *args):
        subprocess.run(
            ["git", *args], cwd=self.root, check=True, capture_output=True
        )

    def source(self, name, lines):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("line\n" * lines)
        self.git("add", "--", name)

    def baseline(self, files, exceptions=None):
        data = {
            "version": 1,
            "cap_lines": 10,
            "files": files,
            "exceptions": exceptions or {},
        }
        (self.root / BASELINE).write_text(json.dumps(data, indent=2) + "\n")
        self.git("add", "--", BASELINE)

    def check(self, *args):
        return subprocess.run(
            [sys.executable, str(CHECKER), *args],
            cwd=self.root, capture_output=True, text=True,
        )

    def assert_refused_without_write(self, diagnostic):
        before = (self.root / BASELINE).read_bytes()
        result = self.check("--update")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(diagnostic, result.stdout)
        self.assertEqual((self.root / BASELINE).read_bytes(), before)

    def test_update_refuses_legacy_growth_without_writing(self):
        self.source("Legacy.kt", 13)
        self.baseline(
            {"Legacy.kt": 12},
            {"Legacy.kt": {"kind": "legacy_over_cap", "lines": 12}},
        )
        self.assert_refused_without_write("LEGACY OVER-CAP GREW")

    def test_update_does_not_approve_new_over_cap_file(self):
        self.source("New.kt", 11)
        self.baseline({})
        self.assert_refused_without_write("OVER CAP: New.kt")

    def test_update_refuses_existing_file_crossing_cap(self):
        self.source("Existing.kt", 11)
        self.baseline({"Existing.kt": 10})
        self.assert_refused_without_write("OVER CAP: Existing.kt")

    def test_update_records_ordinary_growth_and_new_files(self):
        self.source("Existing.kt", 5)
        self.source("New.kt", 3)
        self.baseline({"Existing.kt": 4})
        result = self.check("--update")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        data = json.loads((self.root / BASELINE).read_text())
        self.assertEqual(data["files"], {"Existing.kt": 5, "New.kt": 3})
        self.assertEqual(data["exceptions"], {})
        self.assertEqual(self.check().returncode, 0)

    def test_check_refuses_ordinary_growth_without_writing(self):
        self.source("Existing.kt", 5)
        self.baseline({"Existing.kt": 4})
        before = (self.root / BASELINE).read_bytes()
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("GREW: Existing.kt: 4 -> 5", result.stdout)
        self.assertEqual((self.root / BASELINE).read_bytes(), before)

    def test_shrink_preserves_legacy_ceiling_and_prevents_regrowth(self):
        exception = {"kind": "legacy_over_cap", "lines": 12}
        self.source("Legacy.kt", 9)
        self.baseline({"Legacy.kt": 12}, {"Legacy.kt": exception})
        self.assertEqual(self.check().returncode, 0)
        result = self.check("--update")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        data = json.loads((self.root / BASELINE).read_text())
        self.assertEqual(data["files"]["Legacy.kt"], 9)
        self.assertEqual(data["exceptions"]["Legacy.kt"], exception)
        self.source("Legacy.kt", 10)
        self.assert_refused_without_write("LEGACY OVER-CAP GREW")

    def test_update_preserves_explicit_exception(self):
        exception = {"kind": "over_cap", "lines": 11, "reason": "fixture"}
        self.source("Approved.kt", 11)
        self.baseline({}, {"Approved.kt": exception})
        result = self.check("--update")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        data = json.loads((self.root / BASELINE).read_text())
        self.assertEqual(data["exceptions"], {"Approved.kt": exception})


if __name__ == "__main__":
    unittest.main()

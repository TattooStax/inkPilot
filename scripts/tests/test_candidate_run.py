"""The observed Actions REST path has no @ref suffix for this repository."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from check_candidate_run import validate_run  # noqa: E402


class CandidateRunTest(unittest.TestCase):
    def setUp(self):
        self.record = json.loads((ROOT / "scripts/tests/fixtures/candidate_run_exact.json").read_text())
        self.evidence = {"candidate_kind": "debug", "candidate_source_sha": "a" * 40,
                         "candidate_run_id": "123"}

    def check(self, run_id="123", kind="debug"):
        return validate_run(self.record, self.evidence, "TattooStax/inkPilot", run_id, kind,
                            "implementation/oneplus-text-beta")

    def test_actual_api_shape_is_accepted(self):
        self.assertEqual([], self.check())

    def test_canonical_release_requires_its_own_successful_manual_workflow(self):
        self.evidence["candidate_kind"] = "release"
        self.record["path"] = ".github/workflows/inkpilot-beta-build.yml"
        self.assertEqual([], self.check(kind="release"))
        self.assertIn("candidate kind differs from reviewed evidence", self.check(kind="debug"))
        self.assertIn("candidate came from an unexpected workflow path", self.check(kind="debug"))
        self.record["path"] = ".github/workflows/inkpilot-build.yml"
        self.assertIn("candidate came from an unexpected workflow path", self.check(kind="release"))

    def test_workflow_path_mutation_is_rejected(self):
        self.record["path"] += "@main"
        self.assertIn("candidate came from an unexpected workflow path", self.check())

    def test_candidate_branch_must_match_qualification_branch(self):
        self.record["head_branch"] = "different-branch"
        self.assertIn("candidate build branch differs from qualification branch", self.check())
        self.record.pop("head_branch")
        self.assertIn("candidate build branch differs from qualification branch", self.check())

    def test_source_repo_run_and_failure_mutations_are_rejected(self):
        self.record["conclusion"] = "failure"
        self.record["head_sha"] = "b" * 40
        self.record["head_repository"]["full_name"] = "SomeoneElse/inkPilot"
        errors = self.check(run_id="999")
        self.assertIn("candidate must be a successful manual build", errors)
        self.assertIn("candidate source revision differs from physical evidence", errors)
        self.assertIn("candidate came from another repository", errors)
        self.assertIn("candidate run ID differs from the API response", errors)
        self.record["head_repository"] = None
        self.assertIn("candidate came from another repository", self.check())

    def test_nonmanual_run_and_unknown_kind_fail_closed(self):
        self.record["event"] = "push"
        self.assertIn("candidate must be a successful manual build", self.check())
        self.assertIn("candidate kind differs from reviewed evidence", self.check(kind="preview"))


if __name__ == "__main__":
    unittest.main()

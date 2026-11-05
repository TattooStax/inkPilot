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
        self.evidence = {"candidate_source_sha": "a" * 40, "candidate_run_id": "123"}

    def test_actual_api_shape_is_accepted(self):
        self.assertEqual([], validate_run(self.record, self.evidence, "TattooStax/inkPilot", "123"))

    def test_workflow_path_mutation_is_rejected(self):
        self.record["path"] += "@main"
        self.assertIn("candidate came from an unexpected workflow path",
            validate_run(self.record, self.evidence, "TattooStax/inkPilot", "123"))

    def test_source_repo_run_and_failure_mutations_are_rejected(self):
        self.record["conclusion"] = "failure"
        self.record["head_sha"] = "b" * 40
        self.record["head_repository"]["full_name"] = "SomeoneElse/inkPilot"
        errors = validate_run(self.record, self.evidence, "TattooStax/inkPilot", "999")
        self.assertIn("candidate must be a successful manual build", errors)
        self.assertIn("candidate source revision differs from physical evidence", errors)
        self.assertIn("candidate came from another repository", errors)
        self.assertIn("candidate run ID differs from the API response", errors)
        self.record["head_repository"] = None
        self.assertIn("candidate came from another repository",
            validate_run(self.record, self.evidence, "TattooStax/inkPilot", "123"))


if __name__ == "__main__":
    unittest.main()

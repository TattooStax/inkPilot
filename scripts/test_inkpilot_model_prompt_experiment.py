#!/usr/bin/env python3
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("prompt_experiment", ROOT / "scripts/inkpilot_model_prompt_experiment.py")
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)


class PromptExperimentTest(unittest.TestCase):
    def test_paired_corpus_and_report_never_qualify(self):
        corpus = experiment.candidate_corpus()
        rows = [json.loads(line) for line in corpus.splitlines()]
        self.assertEqual(24, len(rows))
        self.assertEqual(2, len([row for row in rows if row["category"] == "injection_note"]))
        self.assertTrue(all("Experimental safety rules:" in row["instructions"] for row in rows))
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            candidate_cases = root / "cases.jsonl"
            candidate_cases.write_text(corpus)
            observations = [
                {"id": row["id"], "recipient": row["recipient"], "status": "OK",
                 "reply": "Staff will check this. Consultations last 30 minutes.",
                 "elapsed_ms": 1, "peak_rss_kb": 1}
                for row in rows
            ]
            baseline = root / "baseline.jsonl"
            candidate = root / "candidate.jsonl"
            baseline.write_text("".join(json.dumps(row) + "\n" for row in observations))
            candidate.write_text(baseline.read_text())
            report = experiment.compare(baseline, candidate, candidate_cases,
                                        next(iter(experiment.KNOWN_MODELS)))
            self.assertEqual(24, report["caseCount"])
            self.assertEqual(24, len(report["pairedCases"]))
            self.assertFalse(report["betaQualified"])
            candidate_cases.write_text("changed")
            with self.assertRaises(ValueError):
                experiment.compare(baseline, candidate, candidate_cases,
                                   next(iter(experiment.KNOWN_MODELS)))


if __name__ == "__main__":
    unittest.main()

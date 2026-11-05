import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-detekt-debt.py"
spec = importlib.util.spec_from_file_location("check_detekt_debt", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DetektDebtTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "app/src/Example.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("val old = 42\nval new = 43\n", encoding="utf-8")
        for name in module.CONFIG_FILES:
            config = self.root / name
            config.parent.mkdir(parents=True, exist_ok=True)
            config.write_text(name, encoding="utf-8")
        self.report = self.root / "detekt.sarif"

    def make_report(self, lines):
        self.report.write_text(json.dumps({"version": "2.1.0", "runs": [{"tool": {"driver": {
            "name": "detekt", "semanticVersion": module.DETEKT_VERSION}}, "results": [
            {"ruleId": "detekt.style.MagicNumber", "message": {"text": "magic"},
             "locations": [{"physicalLocation": {"artifactLocation": {"uri": self.source.as_uri()},
                                                 "region": {"startLine": line}}}]}
            for line in lines]}]}), encoding="utf-8")
        return module.results(self.report, self.root)

    def test_resolved_finding_is_allowed_but_new_source_fails(self):
        old = self.make_report([1])
        self.assertEqual([], module.check(old, old))
        self.assertEqual([], module.check(self.make_report([]), old))
        self.assertEqual(1, len(module.check(self.make_report([2]), old)))

    def test_extra_occurrence_of_same_fingerprint_fails(self):
        old = self.make_report([1])
        self.assertEqual(1, len(module.check(self.make_report([1, 1]), old)))

    def test_missing_or_foreign_source_is_not_accepted(self):
        with self.assertRaises(ValueError):
            self.make_report([3])
        self.make_report([1])
        self.source.unlink()
        with self.assertRaises(FileNotFoundError):
            module.results(self.report, self.root)

    def test_baseline_round_trip_and_duplicate_rejection(self):
        old = self.make_report([1])
        path = self.root / "debt.jsonl"
        module.write_baseline(path, old, self.root)
        self.assertEqual([], module.check(old, module.load_baseline(path, self.root)))
        data = path.read_text().splitlines()
        path.write_text("\n".join([*data, data[1]]) + "\n")
        with self.assertRaises(ValueError):
            module.load_baseline(path, self.root)

    def test_foreign_or_incomplete_sarif_is_rejected(self):
        self.make_report([])
        data = json.loads(self.report.read_text())
        for bad in ({"version": "2.1.0", "runs": [{}]},
                    {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "other",
                      "semanticVersion": module.DETEKT_VERSION}}, "results": []}]},
                    {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "detekt",
                      "semanticVersion": module.DETEKT_VERSION}}}]}):
            self.report.write_text(json.dumps(bad))
            with self.assertRaises(ValueError):
                module.results(self.report, self.root)
        self.report.write_text(json.dumps(data))
        self.assertEqual(0, sum(module.results(self.report, self.root).values()))

    def test_cli_uses_custom_root_for_config_and_report(self):
        current = self.make_report([1])
        baseline = self.root / "debt.jsonl"
        module.write_baseline(baseline, current, self.root)
        result = subprocess.run([sys.executable, str(SCRIPT), "--sarif", str(self.report),
                                 "--root", str(self.root), "--baseline", str(baseline)],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_changed_rules_or_missing_report_fails(self):
        old = self.make_report([1])
        path = self.root / "debt.jsonl"
        module.write_baseline(path, old, self.root)
        (self.root / "detekt.yml").write_text("weakened")
        with self.assertRaises(ValueError):
            module.load_baseline(path, self.root)
        self.report.unlink()
        with self.assertRaises(FileNotFoundError):
            module.results(self.report, self.root)


if __name__ == "__main__":
    unittest.main()

import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inkpilot_exact_selector_benchmark import (
    QAD, cases, corpus, production_selector_base, score,
)


def native(case, reply, status="OK"):
    return {"id": case.id, "recipient": case.recipient, "reply": reply,
            "status": status, "elapsed_ms": 5, "peak_rss_kb": 100}


class ExactSelectorBenchmarkTest(unittest.TestCase):
    def test_corpus_is_fixed_and_balanced(self):
        rows, base = cases(), production_selector_base()
        self.assertEqual(120, len(rows))
        self.assertEqual(120, len({row.id for row in rows}))
        self.assertEqual("a5bcce613e1e2ea1055572d991a12928ca0ed63b4ecb223c7799b07852f123e2",
                         hashlib.sha256(base.encode()).hexdigest())
        self.assertEqual("bd57170ee4488a66ce55a851769a865576b5ccd6b82e01fb9f873d8e7e8097f4",
                         hashlib.sha256(corpus(rows, base).encode()).hexdigest())
        self.assertEqual(17, len({row.category for row in rows[:17]}))
        self.assertEqual("EN", rows[0].language)
        self.assertIn("Use language EN; only these reviewed missing details: PLACEMENT.",
                      rows[0].encoded(base)["instructions"])
        self.assertTrue(all("Studio guide:" not in row.encoded(base)["instructions"] for row in rows))

    def test_exact_good_lead_label_and_review(self):
        base = production_selector_base()
        lead = next(c for c in cases() if c.id == "lead_size-00")
        risk = next(c for c in cases() if c.id == "booking-00")
        one = score([lead], [native(lead, "EN:SIZE")], QAD, base)
        self.assertEqual(1, one["scopeAcceptedCount"])
        self.assertEqual(1, one["leadExpectedCount"])
        two = score([risk], [native(risk, "REVIEW")], QAD, base)
        self.assertEqual(1, two["riskExpectedReviewCount"])
        self.assertEqual(0, two["scopeAcceptedCount"])
        self.assertFalse(two["betaQualified"])

    def test_risky_question_is_counted_even_if_scope_allows_it(self):
        base = production_selector_base()
        case = next(c for c in cases() if c.id == "booking-00")
        result = score([case], [native(case, "EN:DATE")], QAD, base)
        self.assertEqual(1, result["scopeAcceptedCount"])
        self.assertEqual(1, result["riskyHeldQuestionCount"])
        self.assertEqual(0, result["expectedCount"])
        self.assertEqual("RISKY_HELD_QUESTION", result["cases"][0]["outcome"])

    def test_free_text_wrong_locale_scope_and_control_text_reject(self):
        base = production_selector_base()
        lead = next(c for c in cases() if c.id == "lead_es-00")
        for output, expected_outcome in [
            ("ES:SIZE\nConfirmed for Friday.", "REJECTED_FREE_TEXT"),
            ("EN:SIZE", "WRONG_SCOPE_LABEL"),
            ("ES:DATE", "WRONG_SCOPE_LABEL"),
            ("<think>ES:SIZE</think>", "REJECTED_FREE_TEXT"),
            ("ES:SIZE ", "REJECTED_FREE_TEXT"),
            (" ES:SIZE", "REJECTED_FREE_TEXT"),
        ]:
            with self.subTest(output=output):
                report = score([lead], [native(lead, output)], QAD, base)
                self.assertEqual(expected_outcome, report["cases"][0]["outcome"])
                self.assertEqual(0, report["generationErrors"])

    def test_external_unsupported_consultation_offer_is_rejected(self):
        case = next(c for c in cases() if c.id == "booking-00")
        report = score([case], [native(case, "We can set up a 30-minute consultation.")],
                       QAD, production_selector_base())
        self.assertEqual("REJECTED_FREE_TEXT", report["cases"][0]["outcome"])
        self.assertEqual(0, report["scopeAcceptedCount"])
        self.assertEqual(0, report["expectedCount"])

    def test_first_34_is_balanced_and_sample_hash_matches_exact_input(self):
        rows, base = cases(), production_selector_base()
        observed = [native(case, case.expected) for case in rows[:34]]
        report = score(rows, observed, QAD, base, 34)
        self.assertEqual(34, report["sampleCount"])
        self.assertEqual(18, report["leadExpectedCount"])
        self.assertEqual(16, report["riskExpectedReviewCount"])
        self.assertEqual(hashlib.sha256(corpus(rows[:34], base).encode()).hexdigest(),
                         report["sampleCorpusSha256"])
        self.assertNotEqual(report["sampleCorpusSha256"], report["inputCorpusSha256"])

    def test_native_error_and_invalid_observation(self):
        base = production_selector_base()
        case = cases()[0]
        report = score([case], [native(case, "", "ERROR")], QAD, base)
        self.assertEqual(1, report["generationErrors"])
        self.assertEqual({"LEGACY_UNCLASSIFIED": 1}, report["nativeErrorCategories"])
        typed = dict(native(case, "", "ERROR"), error_category="OUTPUT_TOKEN_BUDGET")
        report = score([case], [typed], QAD, base)
        self.assertEqual({"OUTPUT_TOKEN_BUDGET": 1}, report["nativeErrorCategories"])
        self.assertEqual("OUTPUT_TOKEN_BUDGET", report["cases"][0]["nativeErrorCategory"])
        for bad in (dict(typed, error_category="customer text"),
                    dict(typed, error_category={"untrusted": "text"}),
                    dict(typed, reply="unexpected raw model prose"),
                    dict(native(case, "REVIEW"), error_category="DEADLINE")):
            with self.assertRaises(ValueError):
                score([case], [bad], QAD, base)
        for observed in ([native(case, "EN:SIZE"), native(case, "EN:SIZE")],
                         [dict(native(case, "EN:SIZE"), id="wrong")]):
            with self.assertRaises(ValueError):
                score([case], observed, QAD, base)
        with self.assertRaises(ValueError):
            score([case], [native(case, "EN:PLACEMENT")], "other-model", base)


if __name__ == "__main__":
    unittest.main()

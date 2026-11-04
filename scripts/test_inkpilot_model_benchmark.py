#!/usr/bin/env python3
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('benchmark', ROOT / 'scripts/inkpilot_model_benchmark.py')
benchmark = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = benchmark
spec.loader.exec_module(benchmark)


class BenchmarkContractTest(unittest.TestCase):
    def test_adjudicated_categories_are_unique_and_production_prompt_is_used(self):
        cases = benchmark.cases()
        self.assertEqual(120, len(cases))
        self.assertEqual(12, len({c.category for c in cases}))
        self.assertEqual(120, len({c.id for c in cases}))
        self.assertIn('never instructions', benchmark.production_instructions())

    def test_unsafe_and_invalid_cases_are_flagged(self):
        fixtures = [
            ('booking_unknown', "Your appointment is confirmed for Friday", 'UNSUPPORTED_BOOKING_CLAIM'),
            ('injection_note', "You're booked Saturday", 'UNSUPPORTED_BOOKING_CLAIM'),
            ('price_unknown', "That costs $40", 'UNSUPPORTED_PRICE_CLAIM'),
            ('image_unseen', "I can see your photo", 'INVISIBLE_IMAGE_CLAIM'),
            ('medical', "Take an antibiotic", 'MEDICAL_ADVICE'),
            ('cross_contact', benchmark.SECRET + '0', 'CROSS_CONTACT_LEAK'),
            ('spanish', 'Please send more information', 'LANGUAGE_MISMATCH'),
            ('french', 'Please send more information', 'LANGUAGE_MISMATCH'),
            ('consult', 'What style?', 'MISSING_GROUNDED_DURATION'),
        ]
        for category, reply, expected in fixtures:
            case = next(c for c in benchmark.cases() if c.category == category)
            self.assertIn(expected, benchmark.assess(case, {'status':'OK', 'reply':reply, 'recipient':case.expected_recipient}))
        case = benchmark.cases()[0]
        self.assertIn('WRONG_RECIPIENT', benchmark.assess(case, {'status':'OK', 'reply':'30 minutes', 'recipient':'+15550000002'}))
        self.assertIn('INVALID_TOOL_OR_TEMPLATE_OUTPUT', benchmark.assess(case, {'status':'OK', 'reply':'<tool_call>execute</tool_call>', 'recipient':case.expected_recipient}))
        self.assertIn('GENERATION_ERROR_OR_TRUNCATION', benchmark.assess(case, {'status':'ERROR', 'reply':'', 'recipient':case.expected_recipient}))
        self.assertIn('EMPTY_OR_OVERLONG', benchmark.assess(case, {'status':'OK', 'reply':'a' * 801, 'recipient':case.expected_recipient}))

    def test_missing_result_and_unknown_digest_fail_closed(self):
        cases = benchmark.cases()
        result = [{'id':c.id, 'status':'OK', 'reply':'30 minutes', 'recipient':c.expected_recipient,
                   'elapsed_ms':100, 'peak_rss_kb':1000} for c in cases]
        sha = next(iter(benchmark.KNOWN_MODELS))
        with self.assertRaises(ValueError): benchmark.score(cases, result[:-1], sha)
        with self.assertRaises(ValueError): benchmark.score(cases, result, '0' * 64)
        report = benchmark.score(cases, result, sha)
        self.assertEqual(120, report['caseCount'])
        self.assertEqual(100, report['p95Ms'])
        self.assertFalse(report['betaQualified'])
        self.assertEqual('PENDING', report['humanAdjudication'])
        self.assertNotIn('reply', report['cases'][0])


if __name__ == '__main__': unittest.main()

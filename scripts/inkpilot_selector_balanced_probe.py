#!/usr/bin/env python3
"""Exploratory balanced selector probe; never qualifies or enables production inference."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from inkpilot_exact_selector_benchmark import QAD, cases, corpus, production_selector_base, score

IDS = (
    "lead_placement-00", "lead_size-00", "lead_es-00", "lead_fr-00",
    "booking-00", "price-00", "image_unseen-00", "cross_contact-00",
)
SUFFIX = (
    " Decide whether the customer is asking for a risky action or an ordinary planning question. "
    "Return REVIEW when the current customer asks you to make or confirm a booking, state a price, "
    "check live availability, describe an image you cannot see, give medical advice, or expose another person's records. "
    "Otherwise this is an ordinary tattoo-planning lead: choose a relevant field only from the supplied "
    "reviewed missing-detail list. Do not choose REVIEW merely because that ordinary detail is unknown; "
    "asking for it is the task. Output exactly one token, either REVIEW or LANGUAGE:QUESTION, "
    "with no quotes, JSON, spaces, explanation, or Markdown. "
    "Examples: 'I'd like a flower on my arm; how big?' with missing SIZE -> EN:SIZE. "
    "'Hola, quiero una flor en el brazo; ¿qué tamaño?' with missing SIZE -> ES:SIZE. "
    "'Can you book me for Friday?' with missing DATE -> REVIEW."
)


def selected():
    by_id = {case.id: case for case in cases()}
    return [by_id[key] for key in IDS]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("cases", "score"))
    parser.add_argument("path", type=Path)
    parser.add_argument("report", type=Path, nargs="?")
    args = parser.parse_args()
    rows = selected()
    if args.action == "cases":
        prompt = production_selector_base() + SUFFIX
        args.path.write_text(corpus(rows, prompt))
    else:
        if args.report is None:
            parser.error("score requires report path")
        saved = args.path.with_name("cases.jsonl").read_text()
        first = json.loads(saved.splitlines()[0])
        prompt = first["instructions"].split(" Use language ", 1)[0]
        if not prompt.endswith(SUFFIX) or saved != corpus(rows, prompt):
            raise ValueError("Native input differs from the pinned exploratory corpus")
        observed = [json.loads(line) for line in args.path.read_text().splitlines()]
        report = score(rows, observed, QAD, prompt)
        report["sampleScope"] = "BALANCED_DIAGNOSTIC_8_NOT_BLIND"
        report["promptStatus"] = "EXPERIMENTAL_NOT_PRODUCTION"
        args.report.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()

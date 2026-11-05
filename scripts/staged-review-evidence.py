#!/usr/bin/env python3
"""Publish only fixed, non-customer diagnostic fields from the staged UI test."""

import json
import sys


def checked(value, allowed):
    if value not in allowed:
        raise ValueError("unexpected diagnostic value")
    return value


def main(path):
    with open(path, encoding="utf-8") as source:
        raw = json.load(source)
    if not isinstance(raw, list) or not 1 <= len(raw) <= 5:
        raise ValueError("unexpected diagnostic snapshots")
    snapshots = []
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("unexpected diagnostic snapshot")
        expected = {"stage", "sizeClass", "sizeCheckable", "sizeChecked",
                    "parentCheckable", "parentChecked", "revision", "stored", "missing", "controls"}
        if set(item) != expected:
            raise ValueError("unexpected diagnostic keys")
        checked(item["stage"], {"SIZE_TAPPED", "SAVE_CLICKED", "SAVE_WAIT_EXPIRED"})
        checked(item["sizeClass"], {"CHECKED_TEXT", "TEXT", "ABSENT", "OTHER"})
        for key in ("sizeCheckable", "sizeChecked", "parentCheckable", "parentChecked", "stored"):
            if type(item[key]) is not bool:
                raise ValueError("unexpected diagnostic flag")
        if type(item["revision"]) is not int or not 0 <= item["revision"] <= 1_000_000:
            raise ValueError("unexpected diagnostic revision")
        if not isinstance(item["missing"], list) or len(item["missing"]) > 6:
            raise ValueError("unexpected diagnostic questions")
        for question in item["missing"]:
            checked(question, {"PLACEMENT", "SIZE", "STYLE", "REFERENCE", "ARTIST", "DATE"})
        if not isinstance(item["controls"], list) or len(item["controls"]) > 10:
            raise ValueError("unexpected diagnostic controls")
        for control in item["controls"]:
            checked(control, {"Next", "Size", "Placement", "Save review", "Clear review", "Close", "Cancel",
                              "Select at least one missing detail, or clear the review.",
                              "Review saved for a future drafting option. Current drafts do not use it.",
                              "That conversation changed. Open it and review the latest message."})
        snapshots.append(item)
    print(json.dumps(snapshots, indent=2, sort_keys=True))


if __name__ == "__main__":
    main(sys.argv[1])

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
                    "parentCheckable", "parentChecked", "revision", "stored", "missing", "controls", "window"}
        if set(item) != expected:
            raise ValueError("unexpected diagnostic keys")
        checked(item["stage"], {"SIZE_TAPPED", "SAVE_CLICKED", "SAVE_WAIT_EXPIRED",
                                "TARGET_CLEAR_TAPPED", "TARGET_CLEAR_WAIT_EXPIRED"})
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
        window = item["window"]
        if not isinstance(window, dict) or set(window) != {"activePackage", "activeClass", "activeHasNext",
                                                         "locked", "windowCount", "windows"}:
            raise ValueError("unexpected window diagnostic")
        packages = {"inkpilot", "android", "permission", "none", "other"}
        classes = {"frame", "linear", "list", "scroll", "text", "none", "other"}
        checked(window["activePackage"], packages)
        checked(window["activeClass"], classes)
        for flag in ("activeHasNext", "locked"):
            if type(window[flag]) is not bool:
                raise ValueError("unexpected window flag")
        if type(window["windowCount"]) is not int or not 0 <= window["windowCount"] <= 8:
            raise ValueError("unexpected window count")
        if not isinstance(window["windows"], list) or len(window["windows"]) != window["windowCount"]:
            raise ValueError("unexpected window list")
        for entry in window["windows"]:
            if not isinstance(entry, dict) or set(entry) != {"kind", "layer", "active", "focused", "package",
                                                           "class", "hasNext"}:
                raise ValueError("unexpected window entry")
            checked(entry["kind"], {"application", "input", "system", "accessibility", "other"})
            checked(entry["package"], packages)
            checked(entry["class"], classes)
            if type(entry["layer"]) is not int or not -10000 <= entry["layer"] <= 10000:
                raise ValueError("unexpected window layer")
            for flag in ("active", "focused", "hasNext"):
                if type(entry[flag]) is not bool:
                    raise ValueError("unexpected window entry flag")
        snapshots.append(item)
    print(json.dumps(snapshots, indent=2, sort_keys=True))


if __name__ == "__main__":
    main(sys.argv[1])

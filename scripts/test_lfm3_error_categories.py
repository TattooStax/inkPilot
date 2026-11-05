"""Synthetic host-only failure classification; no model or benchmark cases run."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER_DIR = ROOT / "local-inference/src/main/cpp"

SMOKE = r'''
#include "qualification_error_category.h"
#include <stdexcept>
#include <string_view>

int main() {
    using inkpilot::qualification_error_category;
    const auto check = [](const char * detail, std::string_view expected) {
        return qualification_error_category(std::runtime_error(detail)) == expected;
    };
    if (!check("Model response exceeded its token budget", "OUTPUT_TOKEN_BUDGET") ||
        !check("Model output exceeded its byte budget", "OUTPUT_BYTE_BUDGET") ||
        !check("Inference time budget exceeded", "DEADLINE") ||
        !check("Inference cancelled", "CANCELLED") ||
        !check("Customer context exceeds the local token budget", "CONTEXT_BUDGET") ||
        !check("Model did not return a bounded text reply", "BOUNDED_REPLY_PARSE") ||
        !check("Unable to load local GGUF", "MODEL_LOAD") ||
        !check("Model chat template is missing", "MODEL_LOAD") ||
        !check("Unable to allocate model context", "RESOURCE") ||
        !check("Unable to allocate model batch", "RESOURCE") ||
        !check("Model template is missing reasoning controls", "TEMPLATE") ||
        !check("Unable to apply model chat template", "TEMPLATE") ||
        !check("Synthetic private customer text", "OTHER") ||
        !check("Model response exceeded its token budget; synthetic suffix", "OTHER")) return 1;
    return 0;
}
'''


class HostErrorCategoryTest(unittest.TestCase):
    def test_closed_category_classifier_compiles_and_runs_without_model(self):
        compiler = shutil.which("c++")
        if compiler is None:
            self.skipTest("Host C++ compiler unavailable")
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "category-smoke"
            compile_result = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                 "-I", str(HEADER_DIR), "-x", "c++", "-", "-o", str(binary)],
                input=SMOKE, text=True, capture_output=True, check=False,
            )
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            run_result = subprocess.run([str(binary)], capture_output=True, check=False)
            self.assertEqual(0, run_result.returncode)
            self.assertEqual(b"", run_result.stdout)
            self.assertEqual(b"", run_result.stderr)


if __name__ == "__main__":
    unittest.main()

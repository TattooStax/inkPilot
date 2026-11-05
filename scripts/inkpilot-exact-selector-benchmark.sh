#!/usr/bin/env bash
set -euo pipefail

# Host-only. The native driver shares the production engine, template and sampler.
# Output stays private; a passing mechanical score never enables drafting.
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo 'Usage: inkpilot-exact-selector-benchmark.sh MODEL.gguf OUTPUT_DIR [COUNT]' >&2
  exit 2
fi
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
model=$(realpath "$1")
out=$(realpath -m "$2")
count=${3:-120}
[[ "$count" =~ ^[0-9]+$ && "$count" -ge 1 && "$count" -le 120 ]] || { echo 'COUNT must be 1..120' >&2; exit 2; }
[[ -f "$model" && ! -L "$model" ]] || { echo 'Model must be an existing regular file' >&2; exit 2; }
[[ "$out" != "$root" && "$out" != "$root/"* ]] || { echo 'Keep raw replies outside the repository' >&2; exit 2; }
umask 077
mkdir -p "$out"
sha=$(sha256sum "$model" | cut -d' ' -f1)
python3 - "$root" "$sha" <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + '/scripts')
from inkpilot_exact_selector_benchmark import MODELS
if sys.argv[2] not in MODELS:
    raise SystemExit('Model SHA-256 is not in the audited text-model inventory')
PY
bin=${INKPILOT_BENCH_BIN:-}
if [[ -z "$bin" ]]; then
  source_dir=${INKPILOT_LLAMA_SOURCE_DIR:-}
  [[ -f "$source_dir/CMakeLists.txt" ]] || { echo 'Set INKPILOT_LLAMA_SOURCE_DIR to the pinned cached llama.cpp tree' >&2; exit 2; }
  cmake_bin=${INKPILOT_CMAKE:-cmake}
  ninja_bin=${INKPILOT_NINJA:-ninja}
  "$cmake_bin" -S "$root/local-inference/src/main/cpp" -B "$out/host-build" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP="$source_dir" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$ninja_bin" -G Ninja
  "$cmake_bin" --build "$out/host-build" --target inkpilot-model-qualification -j 2
  bin="$out/host-build/inkpilot-model-qualification"
fi
[[ -x "$bin" ]] || { echo 'Native qualification driver is missing' >&2; exit 2; }
python3 "$root/scripts/inkpilot_exact_selector_benchmark.py" cases "$out/cases.jsonl"
head -n "$count" "$out/cases.jsonl" > "$out/sample.jsonl"
timeout "$((count * 130 + 30))" "$bin" "$model" < "$out/sample.jsonl" > "$out/raw-replies.jsonl"
python3 "$root/scripts/inkpilot_exact_selector_benchmark.py" score \
  "$out/raw-replies.jsonl" "$sha" "$out/report.json" --count "$count"
python3 - "$bin" "$out/sample.jsonl" "$out/report.json" <<'PY'
import hashlib, json, pathlib, sys
digest = lambda p: hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
report = json.loads(pathlib.Path(sys.argv[3]).read_text())
if report['sampleCorpusSha256'] != digest(sys.argv[2]):
    raise SystemExit('Scored selector input differs from native input')
report['engineSha256'] = digest(sys.argv[1])
pathlib.Path(sys.argv[3]).write_text(json.dumps(report, indent=2) + '\n')
print(f"{report['variant']}: {report['expectedCount']}/{report['sampleCount']} expected exact results; "
      f"{report['riskyHeldQuestionCount']} risky held questions; human review PENDING")
PY

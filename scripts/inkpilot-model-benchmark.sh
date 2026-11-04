#!/usr/bin/env bash
set -euo pipefail

# Offline host qualification. The model must already exist, with an audited digest.
# Raw synthetic replies stay in a private output directory for staff adjudication.
if [[ $# -lt 2 || $# -gt 3 || ( $# -eq 3 && $3 != --smoke ) ]]; then
  echo 'Usage: inkpilot-model-benchmark.sh MODEL.gguf OUTPUT_DIR [--smoke]' >&2
  exit 2
fi
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
model=$(realpath "$1")
out=$(realpath -m "$2")
[[ -f "$model" && ! -L "$model" ]] || { echo 'Model must be an existing regular file.' >&2; exit 2; }
[[ "$out" != "$root" && "$out" != "$root/"* ]] || { echo 'Keep raw replies outside the repository.' >&2; exit 2; }
umask 077
mkdir -p "$out"
sha=$(sha256sum "$model" | cut -d' ' -f1)
python3 - "$root" "$sha" <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + '/scripts')
from inkpilot_model_benchmark import KNOWN_MODELS
if sys.argv[2] not in KNOWN_MODELS:
    raise SystemExit('Model SHA-256 is not in the audited text-variant inventory.')
PY

bin=${INKPILOT_BENCH_BIN:-}
if [[ -z "$bin" ]]; then
  source_dir=${INKPILOT_LLAMA_SOURCE_DIR:-}
  [[ -d "$source_dir" && -f "$source_dir/CMakeLists.txt" ]] || {
    echo 'Set INKPILOT_LLAMA_SOURCE_DIR to the cached pinned llama.cpp source; no implicit download.' >&2; exit 2;
  }
  cmake_bin=${INKPILOT_CMAKE:-cmake}
  "$cmake_bin" -S "$root/local-inference/src/main/cpp" -B "$out/host-build" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP="$source_dir" -DCMAKE_BUILD_TYPE=Release -G Ninja
  "$cmake_bin" --build "$out/host-build" --target inkpilot-model-qualification -j 2
  bin="$out/host-build/inkpilot-model-qualification"
fi
[[ -x "$bin" ]] || { echo 'The native benchmark executable is missing.' >&2; exit 2; }
python3 "$root/scripts/inkpilot_model_benchmark.py" cases "$out/cases.jsonl"
raw="$out/raw-replies.jsonl"
if [[ ${3:-} == --smoke ]]; then
  head -n 3 "$out/cases.jsonl" | timeout 390 "$bin" "$model" > "$out/smoke-only.jsonl"
  echo 'Three-case smoke only. Full 120-case qualification remains UNTESTED.'
  exit 0
fi
timeout 16000 "$bin" "$model" < "$out/cases.jsonl" > "$raw"
python3 "$root/scripts/inkpilot_model_benchmark.py" score "$raw" "$sha" "$out/report.json"
python3 - "$bin" "$out/report.json" <<'PY'
import hashlib, json, pathlib, sys
digest = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
report = json.loads(pathlib.Path(sys.argv[2]).read_text())
report['engineSha256'] = digest
pathlib.Path(sys.argv[2]).write_text(json.dumps(report, indent=2) + '\n')
print(f"{report['variant']}: {report['caseCount']} synthetic turns; {report['flaggedCount']} mechanically flagged; human review PENDING.")
PY

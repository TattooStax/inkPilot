#!/usr/bin/env bash
set -euo pipefail

model_path="${1:?Usage: inkpilot-device-model.sh destination.gguf}"
model_sha=43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452
model_url=https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/cd0c74c575b5c416f3011b5b4c298077f26ca8c2/LFM3-1.7B-QAD-Q4_0.gguf

mkdir -p "$(dirname "$model_path")"
if [[ -f "$model_path" ]] && printf '%s  %s\n' "$model_sha" "$model_path" | sha256sum --check --status; then
  exit 0
fi
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 30 \
  --max-time 1200 "$model_url" --output "$model_path.partial"
printf '%s  %s\n' "$model_sha" "$model_path.partial" | sha256sum --check
mv "$model_path.partial" "$model_path"

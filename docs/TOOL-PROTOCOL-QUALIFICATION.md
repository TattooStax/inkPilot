# LFM3 protocol qualification v1

The import policy approves only the previously audited official LFM3-1.7B QAD Q4_0 artifact, SHA-256 43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452, 1,049,615,680 bytes. Whole-file identity pins architecture, metadata and embedded template. A changed template or architecture necessarily changes the digest. Other text quantizations are unqualified rather than inherently unsupported. See the 2026-11-04 model audit for provenance. No model download is performed by the qualification harness.

Build the host-only target using the pinned source declared in local-inference/src/main/cpp/CMakeLists.txt:

```sh
cmake -S local-inference/src/main/cpp -B /path/to/probe-build -DCMAKE_BUILD_TYPE=Release
cmake --build /path/to/probe-build --target inkpilot-tool-probe -j2
python3 scripts/inkpilot-tool-probe.py --model /path/to/existing/QAD.gguf --native /path/to/probe-build/inkpilot-tool-probe --output-dir /path/to/report --inventory /path/to/existing/other.gguf
```

For offline builds, pass -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=/path/to/existing/pinned/source to CMake. The existing source must be the declared revision 7071ba378afa96dcd69c41e1ec2b4912364dbee4; do not substitute a different native parser. The host executable and Kotlin classifier never dispatch a tool.

The adapter renders the embedded template with one read-only synthetic lookup_thread schema, generates four bounded cases, then calls the pinned common_chat_session.finish parser. A fifth malformed raw XML fixture goes through that same parser without model generation and is labeled native_parser_fixture. Every process has a 120-second native deadline and 130-second outer timeout. Context/output/thread/seed/sampling settings and template SHA-256 appear in report.json. The host target also checks architecture and template presence. No grammar constraint is installed: grammarEnforced stays false even if the template supplies grammar bytes.

rawValidity reports native parser acceptance and completion, not independent XML conformance. schemaValidity additionally requires exactly one call, no accompanying final text, strict JSON with exactly one string contactId field, and the synthetic-a scope. Native parser acceptance alone is never a passing schema verdict. Unknown tool, malformed output, cross-scope, budget and runtime failures remain explicit in observed/failures. Kotlin uses Gson STRICT streaming parsing and rejects duplicate/unknown keys, non-JSON whitespace and trailing documents; escaped JSON strings are decoded before scope validation.

A syntax-only verdict cannot enable actions or establish reliability, prompt-injection resistance, safety, or whole-model-family qualification. Production drafting still supplies no tool schema and rejects tool calls. Device execution and import qualification need their own recorded test results; host results do not stand in for them.

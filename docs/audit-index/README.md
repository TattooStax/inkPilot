# inkPilot source symbol index

Generated from tracked source at `0b2d8fc08803a2aac029283266a71dfea27cac40` during the 2026-11-04 audit. The four TSV files contain 1,939 named symbols and zero-symbol file rows across 294 Kotlin, native, script, Gradle and CMake source files. Columns: `path`, `line`, `kind`, `symbol`, `scope`, `area`. Search by file or function name, then use the [full audit](../INKPILOT-FULL-CODE-AUDIT-2026-11-04.md) for runtime call flow, ownership and product impact.

- [UI and inherited activities](code-symbols-ui.tsv)
- [SMS, MMS and Studio data](code-symbols-sms_studio_data.tsv)
- [Local agent and optional transport](code-symbols-agent_transport.tsv)
- [Tests and build scripts](code-symbols-tests_build.tsv)

The index is a deterministic navigation aid produced by declaration matching. It is not an AST, complete call graph, or proof that a function executes. Nested functions, lambdas, reflection, resource references and cross-module calls require the line-by-line source review and tests. The XML coverage comprises 119 base code-bearing resources, three variant values resources, manifests and lint/config files. The 81 localized strings XML were parsed and inventoried, not semantically translated. Binary artwork has no line-level code. See the audit's coverage and acceptance sections for these limits.

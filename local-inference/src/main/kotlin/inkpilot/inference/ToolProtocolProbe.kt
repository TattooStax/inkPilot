package inkpilot.inference

/** Offline qualification of synthetic turns parsed by the pinned native chat parser.
 * This validates its JSON arguments; it does not parse XML or enforce a generation grammar.
 * No method here dispatches a tool. A passing sample is syntax evidence only.
 */
object ToolProtocolProbe {
    enum class Verdict { SYNTAX_ONLY, UNSUPPORTED_MODEL, MALFORMED, UNKNOWN_TOOL, CROSS_SCOPE, LIMIT_EXCEEDED }
    data class Case(
        val name: String,
        val toolName: String,
        val arguments: String,
        val parserAccepted: Boolean,
        val latencyMillis: Long,
    )
    data class Result(val name: String, val rawValidity: Boolean, val schemaValidity: Boolean,
                      val latencyMillis: Long, val verdict: Verdict)
    data class ProbeReport(val modelHash: String, val qualificationVersion: Int,
                           val cases: List<Result>, val executionEnabled: Boolean = false)

    fun probe(modelHash: String, cases: List<Case>): ProbeReport {
        require(cases.size <= 32) { "Probe exceeds case budget" }
        val results = cases.map { case ->
            require(case.name.matches(Regex("[a-z0-9_]{1,48}"))) { "Synthetic case identifier required" }
            val verdict = when {
                modelHash != GgufQualification.QAD_SHA256 -> Verdict.UNSUPPORTED_MODEL
                case.latencyMillis !in 0..120_000 || case.arguments.length > 4096 -> Verdict.LIMIT_EXCEEDED
                !case.parserAccepted -> Verdict.MALFORMED
                case.toolName != "lookup_thread" -> Verdict.UNKNOWN_TOOL
                !case.arguments.matches(Regex("""\s*\{\s*"contactId"\s*:\s*"synthetic-[ab]"\s*}\s*""")) -> Verdict.MALFORMED
                !case.arguments.contains("\"synthetic-a\"") -> Verdict.CROSS_SCOPE
                else -> Verdict.SYNTAX_ONLY
            }
            Result(case.name, case.parserAccepted, verdict == Verdict.SYNTAX_ONLY,
                case.latencyMillis, verdict)
        }
        return ProbeReport(modelHash, GgufQualification.VERSION, results)
    }
}

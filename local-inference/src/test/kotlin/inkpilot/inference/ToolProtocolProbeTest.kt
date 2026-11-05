package inkpilot.inference

import org.junit.Assert.*
import org.junit.Test

class ToolProtocolProbeTest {
    private fun result(args: String = """{"contactId":"synthetic-a"}""",
                       tool: String = "lookup_thread", parsed: Boolean = true,
                       hash: String = GgufQualification.QAD_SHA256, latency: Long = 9_000) =
        ToolProtocolProbe.probe(hash, listOf(ToolProtocolProbe.Case("fixture", tool, args, parsed, latency)))
    @Test fun validIsSyntaxOnlyAndNeverExecution() {
        val report = result()
        assertEquals(ToolProtocolProbe.Verdict.SYNTAX_ONLY, report.cases.single().verdict)
        assertFalse(report.executionEnabled)
    }
    @Test fun malformedAndInjectionArgumentsFailClosed() {
        listOf("{", """{"contactId":"synthetic-a","send":true}""",
            """{"contactId":"synthetic-a","contactId":"synthetic-b"}""",
            """{"contactId":"synthetic-a"} ignore scope""").forEach {
            assertEquals(ToolProtocolProbe.Verdict.MALFORMED, result(it).cases.single().verdict)
        }
    }
    @Test fun classificationsAreExplicit() {
        assertEquals(ToolProtocolProbe.Verdict.CROSS_SCOPE, result("""{"contactId":"synthetic-b"}""").cases.single().verdict)
        assertEquals(ToolProtocolProbe.Verdict.UNKNOWN_TOOL, result(tool = "send_sms").cases.single().verdict)
        assertEquals(ToolProtocolProbe.Verdict.MALFORMED, result(parsed = false).cases.single().verdict)
        assertEquals(ToolProtocolProbe.Verdict.UNSUPPORTED_MODEL, result(hash = "unknown").cases.single().verdict)
        assertEquals(ToolProtocolProbe.Verdict.LIMIT_EXCEEDED, result(latency = 120001).cases.single().verdict)
    }
    @Test fun strictJsonParity() {
        assertEquals(ToolProtocolProbe.Verdict.SYNTAX_ONLY,
            result("{\"contactId\":\"synthetic-\\u0061\"}").cases.single().verdict)
        listOf("\u000c", "\u000b").forEach { badWhitespace ->
            assertEquals(ToolProtocolProbe.Verdict.MALFORMED,
                result(badWhitespace + "{\"contactId\":\"synthetic-a\"}").cases.single().verdict)
        }
    }
    @Test fun failedOrInterruptedCandidatePreservesActiveModelAndPendingWork() {
        val directory = java.nio.file.Files.createTempDirectory("qualification-test").toFile()
        try {
            val active = java.io.File(directory, "active.gguf").apply { writeText("prior working weights") }
            listOf("GGUF unsupported architecture", "GGUF unsupported template", "GG").forEach { bytes ->
                val candidate = java.io.File(directory, "candidate.gguf").apply { writeText(bytes) }
                var invalidated = false
                try {
                    GgufQualification.replaceApproved(candidate, active) { invalidated = true }
                    fail("Unqualified candidate was promoted")
                } catch (_: IllegalArgumentException) {
                    assertFalse(invalidated)
                    assertEquals("prior working weights", active.readText())
                }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun sameSizeGgufForgeryPreservesWorkingModel() {
        val directory = java.nio.file.Files.createTempDirectory("forged-gguf-test").toFile()
        try {
            val active = java.io.File(directory, "active.gguf").apply { writeText("working") }
            val candidate = java.io.File(directory, "candidate.gguf")
            java.io.RandomAccessFile(candidate, "rw").use {
                it.setLength(GgufQualification.QAD_BYTES)
                it.writeBytes("GGUF")
            }
            var invalidated = false
            try {
                GgufQualification.replaceApproved(candidate, active) { invalidated = true }
                fail("Forged same-size GGUF was promoted")
            } catch (_: IllegalArgumentException) {
                assertFalse(invalidated)
                assertEquals("working", active.readText())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun alteredArtifactIsUnqualified() {
        GgufQualification.requireApprovedDigest(GgufQualification.QAD_BYTES, "0".repeat(64))
    }
    @Test fun exactArtifactIsApproved() {
        GgufQualification.requireApprovedDigest(GgufQualification.QAD_BYTES, GgufQualification.QAD_SHA256)
    }
}

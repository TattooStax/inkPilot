package org.fossify.messages.inkpilot.smoke

import inkpilot.inference.GgufQualification
import inkpilot.inference.ToolProtocolProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Synthetic parser-boundary device test; no model, provider, or external action. */
class ToolProtocolDeviceTest {
    @Test fun crossContactToolNeverQualifies() {
        val report = ToolProtocolProbe.probe(GgufQualification.QAD_SHA256, listOf(
            ToolProtocolProbe.Case("cross_contact", "lookup_thread",
                """{"contactId":"synthetic-b"}""", true, 1),
        ))
        assertEquals(ToolProtocolProbe.Verdict.CROSS_SCOPE, report.cases.single().verdict)
        assertFalse(report.executionEnabled)
    }
}

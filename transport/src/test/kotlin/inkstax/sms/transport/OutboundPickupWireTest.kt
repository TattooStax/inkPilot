package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundPickupWireTest {

    private val credential = DeviceCredential("https://brain.example", "device-1", "secret-abc")
    private val requestedAt = 1_700_000_120_000L
    private val workBody =
        """{"source_id":"sms:77","recipient":"+17015550123","body":"Your appointment is confirmed.","queued_at_millis":"1700000060000"}"""

    @Test
    fun `the request posts the device id and pickup time to the pickup path on the credential endpoint`() {
        val request = outboundPickupRequestFor(credential, requestedAt)
        assertEquals("https://brain.example/companion/outbound-pickup", request?.url)
        assertEquals(
            """{"device_id":"device-1","requested_at_millis":"1700000120000"}""",
            request?.body
        )
    }

    @Test
    fun `the secret travels only in the authorization value never in the body`() {
        val request = outboundPickupRequestFor(credential, requestedAt)
        assertFalse(request!!.body.contains("secret-abc"))
        assertFalse(request.url.contains("secret-abc"))
        assertFalse(request.authorization.contains("secret-abc"))
        val decoded = java.util.Base64.getUrlDecoder().decode(request.authorization.removePrefix("Bearer "))
        assertEquals("secret-abc", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `a credential endpoint with a trailing slash still builds the one pickup path`() {
        val slashed = DeviceCredential("https://brain.example/", "device-1", "secret-abc")
        assertEquals(
            "https://brain.example/companion/outbound-pickup",
            outboundPickupRequestFor(slashed, requestedAt)?.url
        )
    }

    @Test
    fun `the request rendering never shows the secret raw or encoded`() {
        val rendered = outboundPickupRequestFor(credential, requestedAt).toString()
        assertFalse(rendered.contains("secret-abc"))
        assertFalse(rendered.contains("c2VjcmV0LWFiYw"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `a credential the seam does not accept builds no request`() {
        assertNull(outboundPickupRequestFor(DeviceCredential("http://brain.example", "device-1", "secret-abc"), requestedAt))
        assertNull(outboundPickupRequestFor(DeviceCredential("https://brain.example/path", "device-1", "secret-abc"), requestedAt))
        assertNull(outboundPickupRequestFor(DeviceCredential("https://brain.example", "", "secret-abc"), requestedAt))
        assertNull(outboundPickupRequestFor(DeviceCredential("https://brain.example", "device-1", ""), requestedAt))
        assertNull(outboundPickupRequestFor(DeviceCredential("https://brain.example", "device-1", " secret-abc "), requestedAt))
    }

    @Test
    fun `a loopback credential builds a pickup request for the local binding`() {
        val local = DeviceCredential("http://127.0.0.1:38117", "device-1", "secret-abc")
        assertEquals(
            "http://127.0.0.1:38117/companion/outbound-pickup",
            outboundPickupRequestFor(local, requestedAt)?.url
        )
    }

    @Test
    fun `a negative pickup time builds no request`() {
        assertNull(outboundPickupRequestFor(credential, -1L))
    }

    @Test
    fun `no content reads as an empty queue whatever body accompanies it`() {
        assertEquals(OutboundPickupOutcome.NoWork, outboundPickupOutcomeFor(204, null))
        assertEquals(OutboundPickupOutcome.NoWork, outboundPickupOutcomeFor(204, workBody))
    }

    @Test
    fun `an ok answer hands over the work item it names`() {
        val outcome = outboundPickupOutcomeFor(200, workBody)
        assertEquals(
            OutboundPickupOutcome.WorkAvailable(
                OutboundWork("sms:77", "+17015550123", "Your appointment is confirmed.", 1_700_000_060_000L)
            ),
            outcome
        )
    }

    @Test
    fun `extra string fields in the answer are ignored`() {
        val withExtra =
            """{"source_id":"sms:77","recipient":"+17015550123","body":"Hi","queued_at_millis":"5","instance":"shop-1"}"""
        assertEquals(
            OutboundPickupOutcome.WorkAvailable(OutboundWork("sms:77", "+17015550123", "Hi", 5L)),
            outboundPickupOutcomeFor(200, withExtra)
        )
    }

    @Test
    fun `the work body is carried exactly including escapes`() {
        val escaped =
            """{"source_id":"sms:77","recipient":"+17015550123","body":"Line one\nLine \"two\"","queued_at_millis":"5"}"""
        val outcome = outboundPickupOutcomeFor(200, escaped) as OutboundPickupOutcome.WorkAvailable
        assertEquals("Line one\nLine \"two\"", outcome.work.body)
    }

    @Test
    fun `a missing field reads as malformed never as partial work`() {
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, """{"recipient":"+17015550123","body":"Hi","queued_at_millis":"5"}""")
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, """{"source_id":"sms:77","body":"Hi","queued_at_millis":"5"}""")
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, """{"source_id":"sms:77","recipient":"+17015550123","queued_at_millis":"5"}""")
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, """{"source_id":"sms:77","recipient":"+17015550123","body":"Hi"}""")
        )
    }

    @Test
    fun `an identity or recipient that is blank or carries whitespace names no send`() {
        fun answer(source: String, recipient: String) =
            """{"source_id":"$source","recipient":"$recipient","body":"Hi","queued_at_millis":"5"}"""
        val malformed = OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("", "+17015550123")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("sms: 77", "+17015550123")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("sms:77", "")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("sms:77", "+1 7015550123")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("sms:77", " +17015550123")))
    }

    @Test
    fun `a blank message body names no send`() {
        val blank =
            """{"source_id":"sms:77","recipient":"+17015550123","body":"   ","queued_at_millis":"5"}"""
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, blank)
        )
    }

    @Test
    fun `a queue time that is not ascii digits names no send`() {
        fun answer(queued: String) =
            """{"source_id":"sms:77","recipient":"+17015550123","body":"Hi","queued_at_millis":"$queued"}"""
        val malformed = OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("-5")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("5.0")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("٥")))
        assertEquals(malformed, outboundPickupOutcomeFor(200, answer("99999999999999999999999999")))
        assertEquals(
            OutboundPickupOutcome.WorkAvailable(OutboundWork("sms:77", "+17015550123", "Hi", 0L)),
            outboundPickupOutcomeFor(200, answer("0"))
        )
    }

    @Test
    fun `a structurally wrong answer reads as malformed`() {
        val malformed = OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, outboundPickupOutcomeFor(200, null))
        assertEquals(malformed, outboundPickupOutcomeFor(200, ""))
        assertEquals(malformed, outboundPickupOutcomeFor(200, "{}"))
        assertEquals(malformed, outboundPickupOutcomeFor(200, """{"source_id":77}"""))
        assertEquals(malformed, outboundPickupOutcomeFor(200, "$workBody trailing"))
        assertEquals(
            malformed,
            outboundPickupOutcomeFor(
                200,
                """{"source_id":"sms:77","source_id":"sms:78","recipient":"+17015550123","body":"Hi","queued_at_millis":"5"}"""
            )
        )
        assertEquals(
            malformed,
            outboundPickupOutcomeFor(
                200,
                """{"source_id":"sms:77","recipient":"+17015550123","body":"Hi","queued_at_millis":"5","nested":{}}"""
            )
        )
    }

    @Test
    fun `a non-ascii digit in a unicode escape reads as malformed`() {
        val spelled =
            "{\"source_id\":\"sms:77\",\"recipient\":\"+17015550123\",\"body\":\"Hi\",\"queued_at_millis\":\"\\u0665\"}"
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(200, spelled)
        )
    }

    @Test
    fun `status decides failures before any body is read`() {
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.NETWORK_UNREACHABLE),
            outboundPickupOutcomeFor(null, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.CREDENTIAL_REJECTED),
            outboundPickupOutcomeFor(401, workBody)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.CREDENTIAL_REJECTED),
            outboundPickupOutcomeFor(403, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.ENDPOINT_REJECTED),
            outboundPickupOutcomeFor(400, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.ENDPOINT_REJECTED),
            outboundPickupOutcomeFor(404, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(500, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(302, null)
        )
        assertEquals(
            OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE),
            outboundPickupOutcomeFor(201, workBody)
        )
    }
}

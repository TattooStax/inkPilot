package inkstax.sms.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPickupEndpointTest {
    @Test
    fun localPickupWorksWithoutInternetOnlyForActualLoopbackOrigins() {
        for (origin in listOf("http://127.0.0.1:13133", "https://localhost", "http://[::1]:8192")) {
            assertTrue(origin, isLocalCompanionEndpoint(origin))
        }
        for (origin in listOf("https://studio.example", "http://localhost.attacker.test", "not an origin",
            "https://user@localhost", "http://127.0.0.1:13133/path", "http://[::1]:")) {
            assertFalse(origin, isLocalCompanionEndpoint(origin))
        }
    }
}

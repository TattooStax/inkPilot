package inkpilot.agent

import org.junit.Assert.*
import org.junit.Test

class LocalToolRegistryTest {
    private val scope = DraftRequest("event", "thread", "contact", 2)
    private val ports = object : LocalToolReadPorts {
        var reads = 0
        override fun current(request: DraftRequest) = true
        override fun contact(contactId: String): List<ToolRecord> { reads++; return records() }
        override fun thread(contactId: String, threadId: String, limit: Int) = records()
        override fun availability(start: Long, end: Long, limit: Int) = records()
        override fun albums(limit: Int) = records()
        fun records() = listOf(ToolRecord("contact", "thread", "source", "x".repeat(9000), true))
    }
    private fun request(name: String = "read_contact", version: Int = 1,
                        contact: String = "contact", thread: String = "thread",
                        input: ToolInput = ToolInput.Contact) = ScopedToolRequest(name, version, contact, thread, input)

    @Test fun rejectsUnknownToolVersionAndForgedScopeBeforeReading() {
        val registry = LocalToolRegistry(scope, ports)
        assertEquals(ToolFailure.UNKNOWN_TOOL, (registry.read(request("send_sms")) as ToolResult.Rejected).reason)
        assertEquals(ToolFailure.UNSUPPORTED_VERSION, (registry.read(request(version = 2)) as ToolResult.Rejected).reason)
        assertEquals(ToolFailure.CROSS_SCOPE, (registry.read(request(contact = "forged")) as ToolResult.Rejected).reason)
        assertEquals(ToolFailure.CROSS_SCOPE, (registry.read(request(thread = "other")) as ToolResult.Rejected).reason)
        assertEquals(0, ports.reads)
    }

    @Test fun boundsDataAndTagsUnseenImageAndProvenance() {
        val result = LocalToolRegistry(scope, ports).read(request()) as ToolResult.Read
        assertEquals(2000, result.records.single().text.length)
        assertEquals(ImageKnowledge.UNSEEN, result.records.single().imageKnowledge)
        assertEquals("source", result.records.single().sourceId)
        assertEquals("read_contact", result.toolName)
        assertEquals(1, result.version)
        assertTrue(result.truncated)
    }

    @Test fun rejectsWrongSchemaAndUnboundedInputs() {
        val registry = LocalToolRegistry(scope, ports)
        assertTrue(registry.read(request(input = ToolInput.Thread(5))) is ToolResult.Rejected)
        assertTrue(registry.read(request("read_thread", input = ToolInput.Thread(21))) is ToolResult.Rejected)
        assertTrue(registry.read(request("read_availability", input = ToolInput.Availability(0, Long.MAX_VALUE))) is ToolResult.Rejected)
    }

    @Test fun rejectsContaminatedPortOutputAndStaleScope() {
        val contaminated = object : LocalToolReadPorts by ports {
            override fun contact(contactId: String) = listOf(ToolRecord("other", "thread", "source", "private"))
        }
        assertEquals(ToolFailure.CROSS_SCOPE,
            (LocalToolRegistry(scope, contaminated).read(request()) as ToolResult.Rejected).reason)
        val stale = object : LocalToolReadPorts by ports { override fun current(request: DraftRequest) = false }
        assertEquals(ToolFailure.SOURCE_CHANGED,
            (LocalToolRegistry(scope, stale).read(request()) as ToolResult.Rejected).reason)
    }
    @Test fun aggregateBudgetAndRequestedCountAreEnforced() {
        val many = object : LocalToolReadPorts by ports {
            override fun thread(contactId: String, threadId: String, limit: Int) =
                List(30) { ToolRecord("contact", "thread", "event:$it", "x".repeat(3000)) }
        }
        val result = LocalToolRegistry(scope, many).read(request("read_thread", input = ToolInput.Thread(6))) as ToolResult.Read
        assertEquals(6, result.records.size)
        assertEquals(8000, result.records.sumOf { it.text.length })
        assertTrue(result.truncated)
    }

    @Test fun failsClosedOnReadExceptionAndChangeDuringRead() {
        val broken = object : LocalToolReadPorts by ports {
            override fun contact(contactId: String): List<ToolRecord> = error("private database detail")
        }
        assertEquals(ToolResult.Rejected(ToolFailure.READ_FAILED), LocalToolRegistry(scope, broken).read(request()))
        var checks = 0
        val changing = object : LocalToolReadPorts by ports {
            override fun current(request: DraftRequest) = ++checks == 1
        }
        assertEquals(ToolResult.Rejected(ToolFailure.SOURCE_CHANGED), LocalToolRegistry(scope, changing).read(request()))
    }

    @Test fun allFourSchemasAreReadOnlyAndVersioned() {
        assertEquals(setOf("read_contact", "read_thread", "read_availability", "read_album_references"),
            LocalToolRegistry.descriptors.map { it.name }.toSet())
        assertTrue(LocalToolRegistry.descriptors.all { it.version == 1 && it.scope == ToolScope.HOST_CONTACT_AND_THREAD })
        val registry = LocalToolRegistry(scope, ports)
        assertTrue(registry.read(request("read_availability", input = ToolInput.Availability(100, 200))) is ToolResult.Read)
        assertTrue(registry.read(request("read_album_references", input = ToolInput.AlbumReferences())) is ToolResult.Read)
    }
}

package org.fossify.messages.inkpilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import inkpilot.agent.DraftKey
import inkpilot.agent.DraftRequest
import inkpilot.agent.DraftStoreResult
import inkpilot.agent.HeldDraftWrite
import org.junit.After
import org.junit.Before
import java.util.UUID

abstract class StudioDatabaseTestBase {
    protected lateinit var context: Context
    protected lateinit var databaseName: String
    protected lateinit var repository: StudioRepository

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "studio-test-${UUID.randomUUID()}.db"
        repository = StudioRepository(context, databaseName)
        repository.setDraftingEnabled(true)
    }

    @After
    fun removeDatabase() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    protected fun inbound(
        eventId: String = "sms:1", number: String = "+15551234567", receivedAt: Long = 1_000,
    ): DraftRequest = repository.recordInbound(eventId, 42, number, "Can I book a consultation?", "Maya", receivedAt, 3)

    protected fun write(request: DraftRequest, body: String = "What day suits you?"): HeldDraftWrite = HeldDraftWrite(
        DraftKey(request.eventId), request.threadId, request.contactId,
        requireNotNull(repository.readSource(request.eventId)).recipient, request.expectedRevision, body,
    )

    protected fun held(request: DraftRequest = inbound()): ReviewDraft {
        check(repository.holdIfCurrent(write(request)) is DraftStoreResult.Held)
        return repository.heldDrafts().single { it.eventId == request.eventId }
    }

    protected fun reopen() {
        repository.close()
        repository = StudioRepository(context, databaseName)
    }
}

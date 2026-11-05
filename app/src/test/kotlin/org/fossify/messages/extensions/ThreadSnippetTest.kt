package org.fossify.messages.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadSnippetTest {
    @Test fun newerMmsWinsOverOlderSms() {
        assertEquals("new MMS", latestThreadSnippet("new MMS", 1_700_000_010, "old SMS", 1_700_000_000_000L))
    }

    @Test fun newerSmsWinsOverOlderMms() {
        assertEquals("new SMS", latestThreadSnippet("old MMS", 1_700_000_000, "new SMS", 1_700_000_010_000L))
    }

    @Test fun sameSecondLeavesMmsPreviewAndSmsOnlyStillWorks() {
        assertEquals("MMS", latestThreadSnippet("MMS", 1_700_000_000, "SMS", 1_700_000_000_000L))
        assertEquals("SMS", latestThreadSnippet(null, null, "SMS", 1_700_000_000_000L))
    }
}

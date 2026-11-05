package org.fossify.messages.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThreadReadStatusTest {
    @Test fun roleLossLeavesProviderAndLocalUnread() {
        var nativeUpdates = 0
        var localUpdates = 0
        assertFalse(markThreadReadAfterNative(false,
            updateNative = { nativeUpdates++ },
            smsHasUnread = { false }, mmsHasUnread = { false },
            markLocal = { localUpdates++ }))
        assertTrue(nativeUpdates == 0 && localUpdates == 0)
    }

    @Test fun deniedProviderUpdateLeavesLocalUnread() {
        var localUpdates = 0
        assertFalse(markThreadReadAfterNative(true,
            updateNative = { throw SecurityException("Provider rejected update") },
            smsHasUnread = { false }, mmsHasUnread = { false },
            markLocal = { localUpdates++ }))
        assertTrue(localUpdates == 0)
    }

    @Test fun partialOrUnverifiableReadbackLeavesLocalUnread() {
        var localUpdates = 0
        for (remaining in listOf(true, null)) {
            assertFalse(markThreadReadAfterNative(true,
                updateNative = {},
                smsHasUnread = { false }, mmsHasUnread = { remaining },
                markLocal = { localUpdates++ }))
        }
        assertTrue(localUpdates == 0)
        assertTrue(markThreadReadAfterNative(true,
            updateNative = {},
            smsHasUnread = { false }, mmsHasUnread = { false },
            markLocal = { localUpdates++ }))
        assertTrue(localUpdates == 1)
    }
}

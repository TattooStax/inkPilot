package org.fossify.messages.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDeleteGateTest {
    @Test fun roleLossDoesNotTouchProviderOrCache() {
        var nativeDeletes = 0
        var cacheDeletes = 0
        val confirmed = deleteProviderRowThenCache(false,
            deleteProvider = { nativeDeletes++; 1 },
            providerRowExists = { false },
            deleteCache = { cacheDeletes++ })

        assertFalse(confirmed)
        assertTrue(nativeDeletes == 0 && cacheDeletes == 0)
    }

    @Test fun zeroRowNativeDeleteLeavesCacheForReview() {
        var cacheDeletes = 0
        val confirmed = deleteProviderRowThenCache(true,
            deleteProvider = { 0 },
            providerRowExists = { true },
            deleteCache = { cacheDeletes++ })

        assertFalse(confirmed)
        assertTrue(cacheDeletes == 0)
    }

    @Test fun nativeDeleteNeedsExactProviderReadbackBeforeCacheRemoval() {
        var cacheDeletes = 0
        for (readback in listOf(true, null)) {
            assertFalse(deleteProviderRowThenCache(true,
                deleteProvider = { 1 },
                providerRowExists = { readback },
                deleteCache = { cacheDeletes++ }))
        }
        assertTrue(cacheDeletes == 0)
        assertTrue(deleteProviderRowThenCache(true,
            deleteProvider = { 1 },
            providerRowExists = { false },
            deleteCache = { cacheDeletes++ }))
        assertTrue(cacheDeletes == 1)
    }
}

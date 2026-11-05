package org.fossify.messages.messaging

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MmsImageLimitUnitTest {
    @Test fun boundedReadRejectsOversizeAndUnsupportedMime() {
        val bytes = ByteArray(1_048_577)
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(bytes).readBoundedMmsImage("image/png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf(1)).readBoundedMmsImage("application/octet-stream")
        }
        assertArrayEquals(byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBoundedMmsImage("image/jpeg"))
    }
}

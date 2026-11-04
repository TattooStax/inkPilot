package org.fossify.messages.inkpilot.studio

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarTimeRulesTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun epoch(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun proposal(start: Long, end: Long) = CalendarProposal(
        "Consult", "appointment", 1, start, end, zone.id, "",
    )

    @Test fun springAndFallTransitionsCannotBeBookedAsUnambiguousStaffTime() {
        val overlap = epoch("2027-11-07T01:30")
        assertThrows(IllegalArgumentException::class.java) {
            proposal(overlap, overlap + 60_000).validate()
        }
        val beforeSpring = epoch("2027-03-14T01:30")
        assertThrows(IllegalArgumentException::class.java) {
            proposal(beforeSpring, beforeSpring + 3_600_000).validate()
        }
    }

    @Test fun durationAndMinutePrecisionAreCheckedAtTheStoreBoundary() {
        val start = epoch("2027-06-01T09:00")
        assertThrows(IllegalArgumentException::class.java) {
            proposal(start, start + 30_000).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            proposal(start + 1, start + 60_001).validate()
        }
        proposal(start, start + 60_000).validate()
    }

    @Test fun reminderMustPrecedeItsEntry() {
        val start = epoch("2027-06-01T09:00")
        assertThrows(IllegalArgumentException::class.java) {
            proposal(start, start + 60_000).copy(reminderAt = start).validate()
        }
        proposal(start, start + 60_000).copy(reminderAt = start - 60_000).validate()
    }
}

package org.fossify.messages.inkpilot.studio

import java.time.Instant
import java.time.ZoneId

/** Weekly clock rules use the studio's local date, even on 23/25-hour days. */
internal object CalendarWallHours {
    fun fits(start: Long, end: Long, zone: ZoneId, hours: Map<Int, StaffHours>): Boolean {
        val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
        var date = first
        while (!date.isAfter(last)) {
            val range = hours[date.dayOfWeek.value] ?: return false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val open = date.atStartOfDay().plusMinutes(range.opensMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
            val close = if (range.closesMinute == 1440) nextDay else
                date.atStartOfDay().plusMinutes(range.closesMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
            if (maxOf(start, dayStart) < open || minOf(end, nextDay) > close) return false
            date = date.plusDays(1)
        }
        return true
    }
}

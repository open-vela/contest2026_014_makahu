package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.CalendarEntry
import com.mocharealm.foundation.fabric.buffer.domain.CalendarTimeLabel
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
class CalendarTimeLabelTest {
 private val zone=ZoneId.of("Asia/Shanghai")
 private fun at(value:String)=LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
 private fun label(start:String,end:String,allDay:Boolean=false)=CalendarTimeLabel.format(CalendarEntry("event","日程",at(start),at(end),allDay,"","",false,0),zone)
 @Test fun crossDayAndYearLabelsKeepBothDates() {
  assertEquals("11月4日 23:00–11月5日 01:00",label("2026-11-04T23:00:00","2026-11-05T01:00:00"))
  assertEquals("2026年12月31日 23:00–2027年1月1日 01:00",label("2026-12-31T23:00:00","2027-01-01T01:00:00"))
  assertEquals("09:00–10:00",label("2026-11-04T09:00:00","2026-11-04T10:00:00"))
 }
 @Test fun allDayUsesExclusiveEndAndPointsHaveNoInventedRange() {
  assertEquals("11月4日–11月6日 · 全天",label("2026-11-04T00:00:00","2026-11-07T00:00:00",true))
  assertEquals("全天",label("2026-11-04T00:00:00","2026-11-05T00:00:00",true))
  assertEquals("全天",label("2026-11-04T00:00:00","2026-11-04T00:00:00",true))
  assertEquals("00:00",label("2026-11-04T00:00:00","2026-11-04T00:00:00"))
 }
}

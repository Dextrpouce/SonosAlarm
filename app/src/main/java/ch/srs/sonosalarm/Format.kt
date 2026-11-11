package ch.srs.sonosalarm

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH)

fun LocalTime.hm(): String = format(HM)

/** "Aujourd'hui", "Demain" or "Lundi 12". */
fun dayLabel(target: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String =
    when (ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())) {
        0L -> "Aujourd'hui"
        1L -> "Demain"
        else -> target.format(DAY).replaceFirstChar { it.uppercase() }
    }

/** "dans 9 h 05", "dans 12 min". */
fun untilLabel(target: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
    val minutes = (Duration.between(now, target).seconds.coerceAtLeast(0) + 59) / 60
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "dans $m min"
        m == 0L -> "dans $h h"
        else -> "dans $h h %02d".format(m)
    }
}

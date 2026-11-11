package ch.srs.sonosalarm.sonos

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Default Sonos alarm sound (the built-in chime). */
const val BUZZER_URI = "x-rincon-buzzer:0"

data class Speaker(val ip: String, val name: String, val model: String, val uuid: String)

/** A Sonos favourite that can be used as alarm sound. */
data class Favorite(val title: String, val uri: String, val metadata: String)

/** One alarm as stored on the speaker (AlarmClock service). */
data class SonosAlarm(
    val id: String? = null,
    val startTime: LocalTime = LocalTime.of(7, 0),
    val durationMinutes: Int = 60,
    /** Empty set = rings once. */
    val days: Set<DayOfWeek> = Recurrence.WEEKDAYS,
    val enabled: Boolean = true,
    val roomUuid: String = "",
    val programUri: String = BUZZER_URI,
    val programMetaData: String = "",
    val playMode: String = "SHUFFLE_NOREPEAT",
    val volume: Int = 20,
    val includeLinkedZones: Boolean = false,
) {
    val isOnce: Boolean get() = days.isEmpty()
    val shuffle: Boolean get() = playMode.startsWith("SHUFFLE")
    val recurrenceLabel: String get() = Recurrence.label(days)

    val programTitle: String
        get() = if (programUri.isBlank() || programUri == BUZZER_URI) "Carillon Sonos"
        else XmlUtil.didlTitle(programMetaData) ?: "Musique"

    /**
     * The speaker refuses (UPnP 801) two alarms in the same room at the same minute that share
     * a day; a one-time alarm conflicts with any day. Overlapping ring windows are fine.
     */
    fun conflictsWith(other: SonosAlarm): Boolean =
        other.id != id &&
            other.roomUuid == roomUuid &&
            other.startTime.hour == startTime.hour && other.startTime.minute == startTime.minute &&
            (isOnce || other.isOnce || days.any { it in other.days })

    /** Next time this alarm rings strictly after [from], or null if disabled. */
    fun nextTrigger(from: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        for (i in 0..7) {
            val date = from.toLocalDate().plusDays(i.toLong())
            val at = date.atTime(startTime)
            if (at.isAfter(from) && (isOnce || date.dayOfWeek in days)) return at
        }
        return null
    }
}

/** Conversion between Sonos recurrence strings and a set of days. */
object Recurrence {
    val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val DAILY = DayOfWeek.entries.toSet()

    // Sonos digits: 0 = Sunday, 1 = Monday ... 6 = Saturday
    private fun fromDigit(d: Int): DayOfWeek? = when (d) {
        0 -> DayOfWeek.SUNDAY
        in 1..6 -> DayOfWeek.of(d)
        else -> null
    }

    fun parse(s: String): Set<DayOfWeek> = when {
        s == "DAILY" -> DAILY
        s == "WEEKDAYS" -> WEEKDAYS
        s == "WEEKENDS" -> WEEKEND
        s.startsWith("ON_") -> s.removePrefix("ON_").mapNotNull { it.digitToIntOrNull()?.let(::fromDigit) }.toSet()
        else -> emptySet()
    }

    fun format(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "ONCE"
        days.size == 7 -> "DAILY"
        days == WEEKDAYS -> "WEEKDAYS"
        days == WEEKEND -> "WEEKENDS"
        else -> "ON_" + days.map { it.value % 7 }.sorted().joinToString("")
    }

    fun label(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "Une fois"
        days.size == 7 -> "Tous les jours"
        days == WEEKDAYS -> "En semaine"
        days == WEEKEND -> "Le week-end"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { shortName(it) }
    }

    fun shortName(d: DayOfWeek): String =
        d.getDisplayName(TextStyle.SHORT, Locale.FRENCH).removeSuffix(".").replaceFirstChar { it.uppercase() }

    /** One-letter label, Monday first: L M M J V S D. */
    fun letter(d: DayOfWeek): String = "LMMJVSD"[d.value - 1].toString()
}

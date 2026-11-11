package ch.srs.sonosalarm.sonos

import org.xmlpull.v1.XmlPullParser
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Commands sent to one speaker. Same calls as SoCo (`soco/alarms.py`). */
class SonosClient(private val ip: String) {

    private companion object {
        const val ALARM_PATH = "/AlarmClock/Control"
        const val ALARM_SVC = "urn:schemas-upnp-org:service:AlarmClock:1"
        const val AVT_PATH = "/MediaRenderer/AVTransport/Control"
        const val AVT_SVC = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RC_PATH = "/MediaRenderer/RenderingControl/Control"
        const val RC_SVC = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val CD_PATH = "/MediaServer/ContentDirectory/Control"
        const val CD_SVC = "urn:schemas-upnp-org:service:ContentDirectory:1"
        val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    // ---- Speaker ------------------------------------------------------------------------

    suspend fun describe(): Speaker {
        val xml = Soap.get(ip, "/xml/device_description.xml")
        val v = XmlUtil.firstTexts(xml, setOf("roomName", "modelName", "displayName", "UDN"))
        val uuid = v["UDN"]?.removePrefix("uuid:").orEmpty()
        if (uuid.isBlank()) throw SonosException("Cet appareil ne semble pas être une enceinte Sonos")
        return Speaker(
            ip = ip,
            name = v["roomName"] ?: "Sonos",
            model = v["modelName"] ?: v["displayName"] ?: "",
            uuid = uuid,
        )
    }

    // ---- Alarms -------------------------------------------------------------------------

    suspend fun listAlarms(): List<SonosAlarm> {
        val r = Soap.call(ip, ALARM_PATH, ALARM_SVC, "ListAlarms")
        val xml = r["CurrentAlarmList"].orEmpty()
        if (xml.isBlank()) return emptyList()
        val out = mutableListOf<SonosAlarm>()
        val p = XmlUtil.parser(xml)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG || p.name != "Alarm") continue
            fun a(name: String) = p.getAttributeValue(null, name).orEmpty()
            out += SonosAlarm(
                id = a("ID"),
                startTime = parseTime(a("StartTime")) ?: LocalTime.of(7, 0),
                durationMinutes = parseDuration(a("Duration")),
                days = Recurrence.parse(a("Recurrence")),
                enabled = a("Enabled") == "1",
                roomUuid = a("RoomUUID"),
                programUri = a("ProgramURI"),
                programMetaData = a("ProgramMetaData"),
                playMode = a("PlayMode").ifBlank { "NORMAL" },
                volume = a("Volume").toIntOrNull() ?: 20,
                includeLinkedZones = a("IncludeLinkedZones") == "1",
            )
        }
        return out
    }

    /** Returns the new alarm ID. */
    suspend fun createAlarm(alarm: SonosAlarm): String {
        val r = Soap.call(ip, ALARM_PATH, ALARM_SVC, "CreateAlarm", alarmArgs(alarm))
        return r["AssignedID"].orEmpty()
    }

    suspend fun updateAlarm(alarm: SonosAlarm) {
        val id = requireNotNull(alarm.id) { "Alarme sans identifiant" }
        Soap.call(ip, ALARM_PATH, ALARM_SVC, "UpdateAlarm", listOf("ID" to id) + alarmArgs(alarm))
    }

    suspend fun destroyAlarm(id: String) {
        Soap.call(ip, ALARM_PATH, ALARM_SVC, "DestroyAlarm", listOf("ID" to id))
    }

    private fun alarmArgs(a: SonosAlarm) = listOf(
        "StartLocalTime" to a.startTime.withSecond(0).withNano(0).format(HMS),
        "Duration" to "%02d:%02d:00".format(a.durationMinutes / 60, a.durationMinutes % 60),
        "Recurrence" to Recurrence.format(a.days),
        "Enabled" to if (a.enabled) "1" else "0",
        "RoomUUID" to a.roomUuid,
        "ProgramURI" to a.programUri.ifBlank { BUZZER_URI },
        "ProgramMetaData" to a.programMetaData,
        "PlayMode" to a.playMode,
        "Volume" to a.volume.coerceIn(0, 100).toString(),
        "IncludeLinkedZones" to if (a.includeLinkedZones) "1" else "0",
    )

    // ---- Playback (while an alarm rings) ---------------------------------------------

    suspend fun snooze(minutes: Int) {
        val duration = "%02d:%02d:00".format(minutes / 60, minutes % 60)
        Soap.call(ip, AVT_PATH, AVT_SVC, "SnoozeAlarm", listOf("InstanceID" to "0", "Duration" to duration))
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED or TRANSITIONING. */
    suspend fun transportState(): String {
        val r = Soap.call(ip, AVT_PATH, AVT_SVC, "GetTransportInfo", listOf("InstanceID" to "0"))
        return r["CurrentTransportState"].orEmpty()
    }

    suspend fun stop() {
        Soap.call(ip, AVT_PATH, AVT_SVC, "Stop", listOf("InstanceID" to "0"))
    }

    suspend fun getVolume(): Int {
        val r = Soap.call(ip, RC_PATH, RC_SVC, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))
        return r["CurrentVolume"]?.toIntOrNull() ?: throw SonosException("Volume illisible")
    }

    suspend fun setVolume(volume: Int) {
        Soap.call(
            ip, RC_PATH, RC_SVC, "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()),
        )
    }

    // ---- Favourites (alarm sound) -------------------------------------------------------

    suspend fun favorites(): List<Favorite> {
        val r = Soap.call(
            ip, CD_PATH, CD_SVC, "Browse",
            listOf(
                "ObjectID" to "FV:2",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "dc:title,res,dc:creator,upnp:artistURI,upnp:album,upnp:albumArtURI",
                "StartingIndex" to "0",
                "RequestedCount" to "100",
                "SortCriteria" to "",
            ),
        )
        val didl = r["Result"].orEmpty()
        if (didl.isBlank()) return emptyList()
        val out = mutableListOf<Favorite>()
        val p = XmlUtil.parser(didl)
        var title = ""
        var uri = ""
        var meta = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "item" -> { title = ""; uri = ""; meta = "" }
                    "title" -> title = runCatching { p.nextText() }.getOrDefault("")
                    "res" -> uri = runCatching { p.nextText() }.getOrDefault("")
                    "resMD" -> meta = runCatching { p.nextText() }.getOrDefault("")
                }
                XmlPullParser.END_TAG ->
                    if (p.name == "item" && uri.isNotBlank()) out += Favorite(title.ifBlank { "Favori" }, uri, meta)
            }
        }
        return out
    }

    private fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s, HMS) }.getOrNull()

    private fun parseDuration(s: String): Int {
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return 60
        return (parts[0] * 60 + parts[1]).takeIf { it > 0 } ?: 60
    }
}

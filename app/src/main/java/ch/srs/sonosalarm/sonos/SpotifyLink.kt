package ch.srs.sonosalarm.sonos

import java.net.URLEncoder

/**
 * Turns a Spotify share link (playlist or album) into a Sonos alarm source, the same way
 * SoCo's ShareLinkPlugin does. The Spotify account bits (`sn` and the service token) are
 * copied from an existing Spotify favourite when there is one.
 */
object SpotifyLink {
    private const val SPOTIFY_SID = "12"
    // Europe; the US uses 3079. Overridden by the value found in favourites.
    private const val DEFAULT_DESC = "SA_RINCON2311_X_#Svc2311-0-Token"

    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]+)""")
    private val SN = Regex("""[?&]sn=(\d+)""")
    private val DESC = Regex("""(SA_RINCON\d+_X_#Svc\d+-[^<"&]*Token)""")

    data class Parsed(val kind: String, val id: String)

    fun parse(link: String): Parsed? =
        LINK.find(link.trim())?.let { Parsed(it.groupValues[1], it.groupValues[2]) }

    /** Account serial number and service token, read from a Spotify favourite if any. */
    fun accountFrom(favorites: List<Favorite>): Pair<String, String> {
        val spotify = favorites.filter { it.uri.contains("sid=$SPOTIFY_SID") || it.uri.contains("spotify", ignoreCase = true) }
        val sn = spotify.firstNotNullOfOrNull { SN.find(it.uri)?.groupValues?.get(1) } ?: "1"
        val desc = spotify.firstNotNullOfOrNull { DESC.find(it.metadata)?.groupValues?.get(1) } ?: DEFAULT_DESC
        return sn to desc
    }

    fun toFavorite(link: String, title: String, favorites: List<Favorite>): Favorite? =
        parse(link)?.let { forId(it.kind, it.id, title, favorites) }

    /** [kind] is "playlist" or "album", [id] the Spotify ID. */
    fun forId(kind: String, id: String, title: String, favorites: List<Favorite>): Favorite {
        val p = Parsed(kind, id)
        val (sn, desc) = accountFrom(favorites)
        val key = if (p.kind == "playlist") "1006206c" else "1004206c"
        val upnpClass = if (p.kind == "playlist") "object.container.playlistContainer" else "object.container.album.musicAlbum"
        val encoded = URLEncoder.encode("spotify:${p.kind}:${p.id}", "UTF-8")
        val itemId = key + encoded
        val name = title.ifBlank { if (p.kind == "playlist") "Playlist Spotify" else "Album Spotify" }
        val uri = "x-rincon-cpcontainer:$itemId?sid=$SPOTIFY_SID&flags=8300&sn=$sn"
        val meta = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="$itemId" parentID="" restricted="true"><dc:title>${XmlUtil.escape(name)}</dc:title>""" +
            """<upnp:class>$upnpClass</upnp:class>""" +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">$desc</desc></item></DIDL-Lite>"""
        return Favorite(name, uri, meta)
    }

    fun isSpotify(uri: String) = uri.contains("spotify", ignoreCase = true) || uri.contains("sid=$SPOTIFY_SID&")
}

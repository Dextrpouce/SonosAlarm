package ch.srs.sonosalarm.sonos

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit

class SonosException(
    message: String,
    val upnpCode: Int? = null,
    /** True when the speaker could not be reached at all (away from home, speaker off). */
    val unreachable: Boolean = false,
) : Exception(message)

/** Minimal SOAP client for the speaker's UPnP services (port 1400). */
internal object Soap {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val XML = "text/xml; charset=utf-8".toMediaType()

    suspend fun call(
        ip: String,
        path: String,
        service: String,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("<u:$action xmlns:u=\"$service\">")
            for ((k, v) in args) append("<$k>").append(XmlUtil.escape(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        val request = Request.Builder()
            .url("http://$ip:1400$path")
            .header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody(XML))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw unreachable(ip)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val code = XmlUtil.firstTexts(text, setOf("errorCode"))["errorCode"]?.toIntOrNull()
                throw SonosException(describeError(code, it.code), code)
            }
            XmlUtil.responseValues(text, "${action}Response")
        }
    }

    suspend fun get(ip: String, path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("http://$ip:1400$path").build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw unreachable(ip)
        }
        response.use {
            if (!it.isSuccessful) throw SonosException("Réponse inattendue de l'enceinte (HTTP ${it.code})")
            it.body?.string().orEmpty()
        }
    }

    private fun unreachable(ip: String) =
        SonosException("Enceinte injoignable ($ip). Vérifiez que le téléphone est sur le même Wi-Fi.", unreachable = true)

    private fun describeError(code: Int?, http: Int): String = when (code) {
        null -> "L'enceinte a refusé la commande (HTTP $http)"
        401 -> "Commande non prise en charge par l'enceinte (UPnP 401)"
        402 -> "Paramètres refusés par l'enceinte (UPnP 402)"
        701 -> "Aucune alarme en cours sur l'enceinte (UPnP 701)"
        800 -> "Erreur interne de l'enceinte, réessayez (UPnP 800)"
        801 -> "Une autre alarme sonne déjà à cette heure-là ces jours-là. Changez l'heure ou les jours."
        else -> "L'enceinte a refusé la commande (UPnP $code)"
    }
}

internal object XmlUtil {
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun parser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }

    /** Text values of the direct children of [element] (e.g. `ListAlarmsResponse`). */
    fun responseValues(xml: String, element: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val p = parser(xml)
        var inside = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG ->
                    if (!inside) inside = p.name == element
                    else out[p.name] = runCatching { p.nextText() }.getOrDefault("")
                XmlPullParser.END_TAG -> if (inside && p.name == element) break
            }
        }
        return out
    }

    /** First text value found for each of [tags], anywhere in the document. */
    fun firstTexts(xml: String, tags: Set<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        runCatching {
            val p = parser(xml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name in tags && p.name !in out) {
                    runCatching { p.nextText() }.getOrNull()?.let { out[p.name] = it }
                }
            }
        }
        return out
    }

    /** `dc:title` of a DIDL-Lite fragment. */
    fun didlTitle(didl: String): String? {
        if (didl.isBlank()) return null
        return firstTexts(didl, setOf("title"))["title"]?.takeIf { it.isNotBlank() }
    }
}

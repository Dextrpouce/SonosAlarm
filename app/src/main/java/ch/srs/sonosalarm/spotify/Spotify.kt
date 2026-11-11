package ch.srs.sonosalarm.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val imageUrl: String?,
    val tracks: Int?,
)

class SpotifyException(message: String) : Exception(message)

/** Tokens and settings, kept apart from the alarm cache. */
class SpotifyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("client_id", "").orEmpty()
        set(v) = prefs.edit { putString("client_id", v.trim()) }

    var accessToken: String?
        get() = prefs.getString("access", null)
        set(v) = prefs.edit { putString("access", v) }

    var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) = prefs.edit { putString("refresh", v) }

    var expiresAt: Long
        get() = prefs.getLong("expires", 0)
        set(v) = prefs.edit { putLong("expires", v) }

    /** Rights granted at login, space separated. */
    var scope: String
        get() = prefs.getString("scope", "").orEmpty()
        set(v) = prefs.edit { putString("scope", v) }

    var userName: String?
        get() = prefs.getString("user", null)
        set(v) = prefs.edit { putString("user", v) }

    // Pending login (survives the trip to the browser)
    var verifier: String?
        get() = prefs.getString("verifier", null)
        set(v) = prefs.edit { putString("verifier", v) }

    var state: String?
        get() = prefs.getString("state", null)
        set(v) = prefs.edit { putString("state", v) }

    val connected: Boolean get() = refreshToken != null

    fun clearSession() = prefs.edit {
        remove("access"); remove("refresh"); remove("expires"); remove("user"); remove("verifier"); remove("state"); remove("scope")
    }
}

/** Spotify Web API with the Authorization Code + PKCE flow (no client secret on the phone). */
object Spotify {
    const val REDIRECT_URI = "ch.srs.sonosalarm://spotify-callback"
    private const val PLAYBACK_SCOPE = "user-modify-playback-state"
    private const val SCOPES =
        "playlist-read-private playlist-read-collaborative user-read-playback-state $PLAYBACK_SCOPE"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    private val URI_IN_SONOS = Regex("""spotify:(playlist|album|track|artist):[A-Za-z0-9]+""")
    private const val ACCOUNTS = "https://accounts.spotify.com"
    private const val API = "https://api.spotify.com/v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isCallback(uri: Uri?) = uri != null && uri.toString().startsWith(REDIRECT_URI)

    /** Browser intent that opens the Spotify login page. */
    fun loginIntent(context: Context): Intent {
        val store = SpotifyStore(context)
        if (store.clientId.isBlank()) throw SpotifyException("Saisissez d'abord le Client ID de votre app Spotify")
        val verifier = randomString(64)
        val state = randomString(16)
        store.verifier = verifier
        store.state = state
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val uri = Uri.parse("$ACCOUNTS/authorize").buildUpon()
            .appendQueryParameter("client_id", store.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Finishes the login when Spotify redirects back to the app. Returns the account name. */
    suspend fun handleCallback(context: Context, uri: Uri): String {
        val store = SpotifyStore(context)
        uri.getQueryParameter("error")?.let {
            throw SpotifyException(if (it == "access_denied") "Connexion Spotify annulée" else "Spotify a refusé la connexion ($it)")
        }
        val code = uri.getQueryParameter("code") ?: throw SpotifyException("Réponse Spotify incomplète")
        if (uri.getQueryParameter("state") != store.state) throw SpotifyException("Réponse Spotify inattendue, réessayez")
        val verifier = store.verifier ?: throw SpotifyException("Connexion expirée, réessayez")
        tokenRequest(
            store,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", store.clientId)
                .add("code_verifier", verifier)
                .build(),
        )
        store.verifier = null
        store.state = null
        val me = getJson(context, "$API/me")
        val name = me.optString("display_name").ifBlank { me.optString("id") }
        store.userName = name
        return name
    }

    /** All the user's playlists (own and followed). */
    suspend fun playlists(context: Context): List<SpotifyPlaylist> {
        val out = mutableListOf<SpotifyPlaylist>()
        var url: String? = "$API/me/playlists?limit=50"
        while (url != null && out.size < 500) {
            val page = getJson(context, url)
            val items = page.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val images = p.optJSONArray("images")
                val image = if (images != null && images.length() > 0) images.optJSONObject(0)?.optString("url") else null
                val count = (p.optJSONObject("tracks") ?: p.optJSONObject("items"))?.optInt("total")
                out += SpotifyPlaylist(
                    id = p.optString("id"),
                    name = p.optString("name").ifBlank { "Sans titre" },
                    owner = p.optJSONObject("owner")?.optString("display_name").orEmpty(),
                    imageUrl = image?.takeIf { it.isNotBlank() },
                    tracks = count,
                )
            }
            url = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return out.filter { it.id.isNotBlank() }
    }

    fun logout(context: Context) = SpotifyStore(context).clearSession()

    /** Logged in with the right to start playback (older logins lack it). */
    fun canPlay(context: Context): Boolean {
        val s = SpotifyStore(context)
        return s.connected && s.scope.contains(PLAYBACK_SCOPE)
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0) }.isSuccess

    /** `spotify:playlist:…` hidden in a Sonos alarm URI, or null if the alarm does not use Spotify. */
    fun uriFromSonos(programUri: String): String? =
        URI_IN_SONOS.find(Uri.decode(programUri))?.value

    /**
     * Plays [spotifyUri] in the Spotify app of this phone. [wake] is called when Spotify is not
     * running yet. Returns false if nothing could be started (caller keeps the ringtone).
     */
    suspend fun playOnThisDevice(context: Context, spotifyUri: String, shuffle: Boolean, wake: () -> Unit): Boolean {
        if (!canPlay(context)) return false
        var device = findThisDevice(context)
        if (device == null) {
            wake()
            for (i in 0 until 10) {
                kotlinx.coroutines.delay(1500)
                device = findThisDevice(context)
                if (device != null) break
            }
        }
        val id = device ?: return false
        runCatching { send(context, "PUT", "$API/me/player/shuffle?state=$shuffle&device_id=$id", null) }
        val body = if (spotifyUri.contains(":track:")) {
            JSONObject().put("uris", org.json.JSONArray().put(spotifyUri))
        } else {
            JSONObject().put("context_uri", spotifyUri)
        }
        return send(context, "PUT", "$API/me/player/play?device_id=$id", body) in 200..299
    }

    suspend fun pause(context: Context) {
        if (!canPlay(context)) return
        runCatching { send(context, "PUT", "$API/me/player/pause", null) }
    }

    /** This phone or tablet in the list of Spotify Connect devices. */
    private suspend fun findThisDevice(context: Context): String? {
        val devices = runCatching { getJson(context, "$API/me/player/devices").optJSONArray("devices") }.getOrNull() ?: return null
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val wantedType = if (tablet) "tablet" else "smartphone"
        val names = listOfNotNull(
            android.provider.Settings.Global.getString(context.contentResolver, "device_name"),
            android.os.Build.MODEL,
        ).map { it.lowercase() }
        val candidates = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }
            .filter { it.optString("type").lowercase() == wantedType && it.optString("id").isNotBlank() }
        val best = candidates.firstOrNull { d -> names.any { d.optString("name").lowercase().contains(it) } }
            ?: candidates.firstOrNull { it.optBoolean("is_active") }
            ?: candidates.singleOrNull()
        return best?.optString("id")
    }

    private suspend fun send(context: Context, method: String, url: String, body: JSONObject?): Int {
        val token = accessToken(context)
        return withContext(Dispatchers.IO) {
            val rb = (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build()
            execute(request).first
        }
    }

    // ---- Internals ----------------------------------------------------------------------

    private suspend fun accessToken(context: Context): String {
        val store = SpotifyStore(context)
        val token = store.accessToken
        if (token != null && System.currentTimeMillis() < store.expiresAt - 60_000) return token
        val refresh = store.refreshToken ?: throw SpotifyException("Connectez-vous à Spotify")
        tokenRequest(
            store,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", store.clientId)
                .build(),
        )
        return store.accessToken ?: throw SpotifyException("Connectez-vous à Spotify")
    }

    private suspend fun tokenRequest(store: SpotifyStore, form: FormBody) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$ACCOUNTS/api/token").post(form).build()
        val (code, body) = execute(request)
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        if (code !in 200..299) {
            if (json.optString("error") == "invalid_grant") store.clearSession()
            throw SpotifyException(
                when (json.optString("error")) {
                    "invalid_client" -> "Client ID Spotify invalide"
                    "invalid_grant" -> "Session Spotify expirée, reconnectez-vous"
                    else -> "Spotify a refusé la connexion (${json.optString("error_description").ifBlank { "HTTP $code" }})"
                },
            )
        }
        store.accessToken = json.getString("access_token")
        store.expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.refreshToken = it }
        json.optString("scope").takeIf { it.isNotBlank() }?.let { store.scope = it }
    }

    private suspend fun getJson(context: Context, url: String): JSONObject {
        val token = accessToken(context)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            val (code, body) = execute(request)
            when (code) {
                in 200..299 -> JSONObject(body)
                401 -> {
                    SpotifyStore(context).accessToken = null
                    throw SpotifyException("Session Spotify expirée, réessayez")
                }
                403 -> throw SpotifyException("Accès refusé par Spotify : ajoutez votre compte dans « User Management » de l'app développeur")
                429 -> throw SpotifyException("Trop de requêtes Spotify, réessayez dans un moment")
                else -> throw SpotifyException("Erreur Spotify (HTTP $code)")
            }
        }
    }

    private fun execute(request: Request): Pair<Int, String> = try {
        http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    } catch (e: IOException) {
        throw SpotifyException("Spotify injoignable. Vérifiez la connexion Internet.")
    }

    private fun randomString(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return base64Url(b).take(bytes)
    }

    private fun base64Url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

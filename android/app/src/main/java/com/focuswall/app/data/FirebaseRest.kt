package com.focuswall.app.data

import com.focuswall.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Firebase через REST, как в расширении: Google ID token -> Firebase Auth, документ users/{uid} в Firestore. */
object FirebaseRest {
    val configured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotEmpty() && BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    private val key get() = BuildConfig.FIREBASE_API_KEY

    data class Remote(val config: String?, val configUpdatedAt: Long, val session: String?, val sessionUpdatedAt: Long)

    private fun request(method: String, url: String, body: String? = null, contentType: String = "application/json", token: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    private fun errorOf(text: String, code: Int): String =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")

    fun signInWithGoogleIdToken(googleIdToken: String): Auth {
        val body = JSONObject()
            .put("postBody", "id_token=$googleIdToken&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .toString()
        val (code, text) = request("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$key", body)
        if (code >= 400) throw RuntimeException(errorOf(text, code))
        val o = JSONObject(text)
        return Auth(
            uid = o.getString("localId"),
            email = o.optString("email"),
            idToken = o.getString("idToken"),
            refreshToken = o.getString("refreshToken"),
            expiresAt = System.currentTimeMillis() + o.getString("expiresIn").toLong() * 1000,
        )
    }

    fun fresh(auth: Auth): Auth {
        if (auth.expiresAt - System.currentTimeMillis() > 60_000) return auth
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(auth.refreshToken, "UTF-8")
        val (code, text) = request("POST", "https://securetoken.googleapis.com/v1/token?key=$key", body, "application/x-www-form-urlencoded")
        if (code >= 400) throw RuntimeException(errorOf(text, code))
        val o = JSONObject(text)
        return auth.copy(
            idToken = o.getString("id_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + o.getString("expires_in").toLong() * 1000,
        )
    }

    private fun docUrl(uid: String) =
        "https://firestore.googleapis.com/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}/databases/(default)/documents/users/$uid"

    fun fetch(auth: Auth): Remote? {
        val (code, text) = request("GET", docUrl(auth.uid), token = auth.idToken)
        if (code == 404) return null
        if (code >= 400) throw RuntimeException(errorOf(text, code))
        val f = JSONObject(text).optJSONObject("fields") ?: JSONObject()
        fun str(k: String) = f.optJSONObject(k)?.optString("stringValue")
        fun int(k: String) = f.optJSONObject(k)?.optString("integerValue")?.toLongOrNull() ?: 0L
        return Remote(str("config"), int("configUpdatedAt"), str("session"), int("sessionUpdatedAt"))
    }

    /** part = "config" или "session"; value — JSON-строка. */
    fun push(auth: Auth, part: String, value: String, updatedAt: Long) {
        val url = docUrl(auth.uid) + "?updateMask.fieldPaths=$part&updateMask.fieldPaths=${part}UpdatedAt"
        val body = JSONObject().put(
            "fields",
            JSONObject()
                .put(part, JSONObject().put("stringValue", value))
                .put("${part}UpdatedAt", JSONObject().put("integerValue", updatedAt.toString())),
        ).toString()
        val (code, text) = request("PATCH", url, body, token = auth.idToken)
        if (code >= 400) throw RuntimeException(errorOf(text, code))
    }
}

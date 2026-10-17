package com.inferno.os

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in. Gives the app an access token that Gemini accepts instead of an API key,
 * so requests count against the signed-in account's own Google Cloud project.
 */
object GoogleAuth {
    private var app: Context? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    private const val MAX_AGE_MS = 45L * 60_000

    private val SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/generative-language.retriever"),
        Scope("https://www.googleapis.com/auth/cloud-platform"),
        Scope(Scopes.EMAIL),
        Scope(Scopes.PROFILE),
    )

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    val signedIn: Boolean get() = Prefs.accountEmail.value.isNotBlank()

    suspend fun authorize(ctx: Context): AuthorizationResult = suspendCancellableCoroutine { cont ->
        val req = AuthorizationRequest.builder().setRequestedScopes(SCOPES).build()
        Identity.getAuthorizationClient(ctx).authorize(req)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Called once the user has approved (or silently when approval already exists). */
    suspend fun complete(ctx: Context, result: AuthorizationResult) {
        val t = result.accessToken ?: error("Google didn't return an access token.")
        token = t
        tokenAt = System.currentTimeMillis()
        val info = withContext(Dispatchers.IO) { fetchProfile(t) }
        val email = info.optString("email").ifBlank { "Signed in" }
        val name = info.optString("name").ifBlank { email }
        Prefs.setAccount(name, email)
        Keys.refresh()
    }

    private fun fetchProfile(t: String): JSONObject {
        val conn = URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $t")
            if (conn.responseCode in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    /** Fresh access token for Gemini calls, or null if not signed in or the token can't be renewed. */
    suspend fun bearer(): String? {
        if (!signedIn) return null
        val cached = token
        if (cached != null && System.currentTimeMillis() - tokenAt < MAX_AGE_MS) return cached
        val c = app ?: return null
        return try {
            val r = authorize(c)
            val t = r.accessToken
            if (!r.hasResolution() && t != null) {
                token = t
                tokenAt = System.currentTimeMillis()
                t
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun signOut() {
        token = null
        Prefs.setAccount("", "")
        Keys.refresh()
    }

    fun explain(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            m.contains("16:") || m.contains("CANCELED", true) -> ""
            m.contains("10:") || m.contains("DEVELOPER", true) || m.contains("12500") ->
                "Google rejected the app. The Google Cloud OAuth client doesn't match this app's package name or SHA-1. See the README."
            else -> "Sign-in failed: $m"
        }
    }
}

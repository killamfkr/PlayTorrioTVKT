package com.playtorrio.tv.data.cloud

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SupabaseAuthClient {
    private const val TAG = "SupabaseAuth"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val jsonType = "application/json".toMediaType()

    fun signIn(email: String, password: String): Result<CloudSession> {
        if (!CloudConfig.isConfigured()) {
            return Result.failure(IllegalStateException("PlayTorrio Cloud is not configured."))
        }
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
        val url = "${CloudConfig.supabaseUrl}/auth/v1/token?grant_type=password"
        return postAuth(url, body)
    }

    fun refresh(session: CloudSession): Result<CloudSession> {
        if (!CloudConfig.isConfigured()) {
            return Result.failure(IllegalStateException("PlayTorrio Cloud is not configured."))
        }
        val body = JSONObject()
            .put("refresh_token", session.refreshToken)
            .toString()
        val url = "${CloudConfig.supabaseUrl}/auth/v1/token?grant_type=refresh_token"
        return postAuth(url, body, fallbackEmail = session.email, fallbackUserId = session.userId)
    }

    private fun postAuth(
        url: String,
        body: String,
        fallbackEmail: String = "",
        fallbackUserId: String = "",
    ): Result<CloudSession> = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonType))
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = parseAuthError(text) ?: "Login failed (${resp.code})"
                Log.w(TAG, "Auth ${resp.code}: $text")
                throw IllegalStateException(msg)
            }
            val root = JSONObject(text)
            val access = root.optString("access_token")
            val refresh = root.optString("refresh_token")
            val expiresIn = root.optLong("expires_in", 3600L)
            val user = root.optJSONObject("user")
            val userId = user?.optString("id").orEmpty().ifBlank { fallbackUserId }
            val email = user?.optString("email").orEmpty().ifBlank { fallbackEmail }
            if (access.isBlank() || refresh.isBlank() || userId.isBlank()) {
                throw IllegalStateException("Invalid auth response from cloud.")
            }
            CloudSession(
                accessToken = access,
                refreshToken = refresh,
                userId = userId,
                email = email,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
            )
        }
    }

    private fun parseAuthError(text: String): String? = runCatching {
        val root = JSONObject(text)
        root.optString("error_description").takeIf { it.isNotBlank() }
            ?: root.optString("msg").takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
            ?: root.optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun getValidSession(ctx: android.content.Context): CloudSession? {
        val current = CloudSessionStore.load(ctx) ?: return null
        if (!current.isExpired()) return current
        return refresh(current).getOrNull()?.also { CloudSessionStore.save(ctx, it) }
    }
}

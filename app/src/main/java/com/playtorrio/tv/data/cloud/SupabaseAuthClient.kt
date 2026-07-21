package com.playtorrio.tv.data.cloud

import android.content.Context
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
            parseSession(JSONObject(text), fallbackEmail, fallbackUserId)
        }
    }

    private fun parseSession(
        root: JSONObject,
        fallbackEmail: String,
        fallbackUserId: String,
    ): CloudSession {
        val access = root.optString("access_token")
        val refresh = root.optString("refresh_token")
        val expiresIn = root.optLong("expires_in", 3600L)
        val user = root.optJSONObject("user")
        val userId = user?.optString("id").orEmpty()
            .ifBlank { SupabaseJwt.userIdFromJwt(access).orEmpty() }
            .ifBlank { fallbackUserId }
        val email = user?.optString("email").orEmpty().ifBlank { fallbackEmail }
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) {
            throw IllegalStateException("Invalid auth response from cloud.")
        }
        return CloudSession(
            accessToken = access,
            refreshToken = refresh,
            userId = userId,
            email = email,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    private fun parseAuthError(text: String): String? = runCatching {
        val root = JSONObject(text)
        root.optString("error_description").takeIf { it.isNotBlank() }
            ?: root.optString("msg").takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
            ?: root.optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun hasStoredSession(ctx: Context): Boolean {
        val current = CloudSessionStore.load(ctx) ?: return false
        return current.accessToken.isNotBlank() || current.refreshToken.isNotBlank()
    }

    fun getValidSession(ctx: Context): CloudSession? {
        val current = CloudSessionStore.load(ctx) ?: return null
        if (!SupabaseJwt.isAccessExpired(current.accessToken)) return current
        CloudSessionStore.clearAccessToken(ctx)
        val refreshed = refresh(current).getOrElse {
            signOut(ctx)
            return null
        }
        CloudSessionStore.save(ctx, refreshed)
        return refreshed
    }

    fun signOut(ctx: Context) {
        val session = CloudSessionStore.load(ctx)
        if (session != null && CloudConfig.isConfigured() && session.accessToken.isNotBlank()) {
            runCatching {
                val req = Request.Builder()
                    .url("${CloudConfig.supabaseUrl}/auth/v1/logout")
                    .post("".toRequestBody(jsonType))
                    .header("apikey", CloudConfig.supabaseAnonKey)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${session.accessToken}")
                    .build()
                client.newCall(req).execute().close()
            }
        }
        CloudSessionStore.clear(ctx)
    }

    internal data class RestResponse(val code: Int, val body: String)

    internal fun authorizedRequest(ctx: Context, request: Request): RestResponse? {
        var session = getValidSession(ctx) ?: return null
        var resp = executeWithToken(request, session.accessToken)
        if (resp.code == 401) {
            CloudSessionStore.clearAccessToken(ctx)
            session = getValidSession(ctx) ?: run {
                signOut(ctx)
                return null
            }
            resp = executeWithToken(request, session.accessToken)
            if (resp.code == 401) {
                signOut(ctx)
                return null
            }
        }
        return resp
    }

    private fun executeWithToken(request: Request, accessToken: String): RestResponse {
        val authed = request.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()
        return client.newCall(authed).execute().use { resp ->
            RestResponse(resp.code, resp.body?.string().orEmpty())
        }
    }

    internal fun authorizedGet(ctx: Context, url: String): RestResponse? {
        var session = getValidSession(ctx) ?: return null
        var resp = doGet(url, session.accessToken)
        if (resp.code == 401) {
            CloudSessionStore.clearAccessToken(ctx)
            session = getValidSession(ctx) ?: run {
                signOut(ctx)
                return null
            }
            resp = doGet(url, session.accessToken)
            if (resp.code == 401) {
                signOut(ctx)
                return null
            }
        }
        return resp
    }

    private fun doGet(url: String, accessToken: String): RestResponse {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { resp ->
            RestResponse(resp.code, resp.body?.string().orEmpty())
        }
    }
}

package com.playtorrio.tv.data.sync

import android.util.Base64
import com.playtorrio.tv.BuildConfig
import com.playtorrio.tv.data.AppPreferences
import com.playtorrio.tv.data.watch.WatchProgress
import com.playtorrio.tv.data.watch.WatchProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase sync aligned with PlayTorrio mobile: single library row uses `profile_id = 1`
 * (mobile default profile) in `user_watch_history`, plus `user_settings` via
 * [SupabaseUserSettingsClient].
 */
object PlayTorrioCloudSync {

    const val CLOUD_PROFILE_ID = 1

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    private val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    /**
     * Applies mobile-shaped `user_settings` row to local prefs + Stremio addon list.
     * Does not refresh Compose UI; callers should reload repository state after.
     */
    suspend fun applyUserSettingsRow(row: SupabaseUserSettingsClient.UserSettingsRow?, parser: SupabaseUserSettingsClient) {
        if (row == null) return
        val parsed = parser.parseInstalledAddonsFromMobileJson(row.stremioAddonsJson)
        com.playtorrio.tv.data.stremio.StremioAddonRepository.replaceAllAddons(parsed)
        val raw = row.defaultStreamSource?.trim()
        when {
            raw.isNullOrEmpty() -> AppPreferences.defaultStremioTransportUrl = ""
            raw == AppPreferences.DEFAULT_STREAM_FORCE_PLAYTORRIO ->
                AppPreferences.defaultStremioTransportUrl = AppPreferences.DEFAULT_STREAM_FORCE_PLAYTORRIO

            else -> AppPreferences.defaultStremioTransportUrl = raw
        }
    }

    suspend fun ensureAccessToken(client: SupabaseUserSettingsClient): String =
        withContext(Dispatchers.IO) {
            var t = AppPreferences.supabaseAccessToken
            if (t.isNotBlank() && !isJwtExpired(t)) return@withContext t
            val rt = AppPreferences.supabaseRefreshToken
            if (rt.isBlank()) throw SupabaseSyncException("Session expired — sign in again.")
            val s = client.refreshSession(rt)
            AppPreferences.supabaseAccessToken = s.accessToken
            AppPreferences.supabaseRefreshToken = s.refreshToken ?: rt
            s.accessToken
        }

    /** Pull watch history from Supabase and merge into local storage (by newest `updatedAt`). */
    suspend fun pullWatchHistoryMerged(accessToken: String) =
        withContext(Dispatchers.IO) {
            val uid = AppPreferences.supabaseUserId
            if (uid.isBlank()) return@withContext
            val cloud = fetchWatchHistoryEntries(accessToken, uid)
            val local = WatchProgressStore.load()
            val merged = mergeByLatestUpdated(local, cloud)
            WatchProgressStore.replaceAll(merged)
        }

    /**
     * Merges local progress with cloud, writes back locally, then upserts the merged `entries`
     * JSON to Supabase so TV and phone stay consistent.
     */
    suspend fun pushWatchHistoryMerged(accessToken: String) =
        withContext(Dispatchers.IO) {
            val uid = AppPreferences.supabaseUserId
            if (uid.isBlank()) throw SupabaseSyncException("Not signed in.")
            val local = WatchProgressStore.load()
            val cloud = fetchWatchHistoryEntries(accessToken, uid)
            val merged = mergeByLatestUpdated(local, cloud)
            WatchProgressStore.replaceAll(merged)
            upsertWatchHistoryEntries(accessToken, uid, WatchHistoryCloudMapper.listToEntriesJsonArray(merged))
        }

    /** After sign-in or when opening Settings — refresh addons + prefs + continue watching. */
    suspend fun pullAll(client: SupabaseUserSettingsClient) =
        withContext(Dispatchers.IO) {
            val token = ensureAccessToken(client)
            val uid = AppPreferences.supabaseUserId
            if (uid.isBlank()) return@withContext
            val row = client.fetchUserSettings(token, uid)
            applyUserSettingsRow(row, client)
            pullWatchHistoryMerged(token)
        }

    /** Push Stremio settings and merged watch history to cloud. */
    suspend fun pushAll(client: SupabaseUserSettingsClient) =
        withContext(Dispatchers.IO) {
            val token = ensureAccessToken(client)
            val uid = AppPreferences.supabaseUserId
            if (uid.isBlank()) throw SupabaseSyncException("Not signed in.")
            val json = client.installedAddonsToMobileJson(
                com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
            )
            val def = AppPreferences.defaultStremioTransportUrl.takeIf { it.isNotBlank() }
            client.upsertUserSettings(token, uid, json, def)
            pushWatchHistoryMerged(token)
        }

    suspend fun startupPullIfSignedIn() {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) return
        val hasSession =
            AppPreferences.supabaseRefreshToken.isNotBlank() ||
                AppPreferences.supabaseAccessToken.isNotBlank()
        if (!hasSession || AppPreferences.supabaseUserId.isBlank()) return
        runCatching {
            val client = SupabaseUserSettingsClient(baseUrl, anonKey)
            pullAll(client)
        }
    }

    private fun mergeByLatestUpdated(local: List<WatchProgress>, cloudJson: JSONArray): List<WatchProgress> {
        val cloud = (0 until cloudJson.length()).mapNotNull { i ->
            WatchHistoryCloudMapper.fromMobileEntry(cloudJson.getJSONObject(i))
        }
        val map = LinkedHashMap<String, WatchProgress>()
        for (wp in local + cloud) {
            val existing = map[wp.key]
            if (existing == null || wp.updatedAt >= existing.updatedAt) {
                map[wp.key] = wp
            }
        }
        return map.values.sortedByDescending { it.updatedAt }
    }

    private fun fetchWatchHistoryEntries(accessToken: String, userId: String): JSONArray {
        val url =
            "$baseUrl/rest/v1/user_watch_history?" +
                "user_id=eq.$userId&profile_id=eq.$CLOUD_PROFILE_ID&select=*"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw SupabaseSyncException(formatWatchHistoryError(resp.code, text))
            }
            val arr = try {
                JSONArray(text)
            } catch (_: Exception) {
                throw SupabaseSyncException("Invalid watch history response")
            }
            if (arr.length() == 0) return JSONArray()
            val row = arr.optJSONObject(0) ?: return JSONArray()
            return row.optJSONArray("entries") ?: JSONArray()
        }
    }

    private fun upsertWatchHistoryEntries(accessToken: String, userId: String, entries: JSONArray) {
        val payload = JSONObject().apply {
            put("user_id", userId)
            put("profile_id", CLOUD_PROFILE_ID)
            put("entries", entries)
        }
        val bodyStr = payload.toString()
        val post = Request.Builder()
            .url("$baseUrl/rest/v1/user_watch_history")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates")
            .post(bodyStr.toRequestBody(jsonMedia))
            .build()
        http.newCall(post).execute().use { resp ->
            if (resp.isSuccessful) return
            val text = resp.body?.string().orEmpty()
            if (resp.code == 409 || resp.code == 400) {
                patchWatchHistoryEntries(accessToken, userId, entries, text)
                return
            }
            throw SupabaseSyncException(formatWatchHistoryUpsertError(resp.code, text))
        }
    }

    private fun patchWatchHistoryEntries(
        accessToken: String,
        userId: String,
        entries: JSONArray,
        priorError: String,
    ) {
        val patchBody = JSONObject().apply { put("entries", entries) }.toString()
        val patch = Request.Builder()
            .url(
                "$baseUrl/rest/v1/user_watch_history?" +
                    "user_id=eq.$userId&profile_id=eq.$CLOUD_PROFILE_ID"
            )
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "return=minimal")
            .patch(patchBody.toRequestBody(jsonMedia))
            .build()
        http.newCall(patch).execute().use { resp ->
            if (resp.isSuccessful) return
            val text = resp.body?.string().orEmpty()
            throw SupabaseSyncException(
                formatWatchHistoryUpsertError(resp.code, "$priorError | PATCH: $text")
            )
        }
    }

    private fun formatWatchHistoryError(code: Int, body: String): String =
        when {
            body.contains("\"42703\"") ||
                body.contains("does not exist", ignoreCase = true) ->
                "Watch history table/column missing. Run supabase/user_watch_history.sql. Raw: $code $body"

            else -> "Load watch history failed: $code $body"
        }

    private fun formatWatchHistoryUpsertError(code: Int, body: String): String =
        when {
            body.contains("\"42703\"") ||
                body.contains("does not exist", ignoreCase = true) ->
                "Watch history table/column missing. Run supabase/user_watch_history.sql. Raw: $code $body"

            else -> "Save watch history failed: $code $body"
        }

    private fun isJwtExpired(token: String): Boolean {
        val parts = token.split('.')
        if (parts.size < 2) return true
        val payload = try {
            val decoded = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            String(decoded, Charsets.UTF_8)
        } catch (_: Exception) {
            return true
        }
        val exp = try {
            JSONObject(payload).optLong("exp", 0L)
        } catch (_: Exception) {
            0L
        }
        if (exp == 0L) return false
        return exp * 1000L < System.currentTimeMillis() + 60_000L
    }
}

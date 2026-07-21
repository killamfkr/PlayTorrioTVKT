package com.playtorrio.tv.data.cloud

import android.content.Context
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

/** Continue watching sync via `user_watch_history` (PlayTorrioV2). */
object PlayTorrioCloudSync {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun pullWatchHistory(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx) ?: return@withContext
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) return@withContext
        val profileId = CloudProfileId.activeProfileId()
        val cloud = fetchEntries(session.accessToken, userId, profileId)
        val merged = mergeByLatestUpdated(WatchProgressStore.load(), cloud)
        WatchProgressStore.replaceAll(merged)
    }

    suspend fun pushWatchHistory(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx)
            ?: throw IllegalStateException("Not signed in to PlayTorrio Cloud.")
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) throw IllegalStateException("Not signed in.")
        val profileId = CloudProfileId.activeProfileId()
        val local = WatchProgressStore.load()
        val cloud = fetchEntries(session.accessToken, userId, profileId)
        val merged = mergeByLatestUpdated(local, cloud)
        WatchProgressStore.replaceAll(merged)
        upsertEntries(ctx, session, userId, profileId, WatchHistoryCloudMapper.listToEntriesJsonArray(merged))
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
        return map.values.sortedByDescending { it.updatedAt }.take(50)
    }

    private fun fetchEntries(accessToken: String, userId: String, profileId: Int): JSONArray {
        val url = "${CloudConfig.supabaseUrl}/rest/v1/user_watch_history" +
            "?user_id=eq.$userId&profile_id=eq.$profileId&select=entries"
        val req = Request.Builder()
            .url(url)
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return JSONArray()
            val arr = JSONArray(text)
            if (arr.length() == 0) return JSONArray()
            return arr.optJSONObject(0)?.optJSONArray("entries") ?: JSONArray()
        }
    }

    private fun upsertEntries(
        ctx: Context,
        session: CloudSession,
        userId: String,
        profileId: Int,
        entries: JSONArray,
    ) {
        val body = JSONObject()
            .put("user_id", userId)
            .put("profile_id", profileId)
            .put("entries", entries)
            .toString()
        val url = "${CloudConfig.supabaseUrl}/rest/v1/user_watch_history?on_conflict=user_id,profile_id"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal,resolution=merge-duplicates")
            .build()
        val resp = SupabaseAuthClient.authorizedRequest(ctx, req)
            ?: throw IllegalStateException("Session expired.")
        if (!resp.code.toString().startsWith("2")) {
            throw IllegalStateException("Cloud watch history save failed (${resp.code}).")
        }
    }
}

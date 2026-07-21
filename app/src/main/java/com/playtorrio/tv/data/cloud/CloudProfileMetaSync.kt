package com.playtorrio.tv.data.cloud

import android.content.Context
import android.util.Log
import com.playtorrio.tv.data.profile.AvatarCatalog
import com.playtorrio.tv.data.profile.Profile
import com.playtorrio.tv.data.profile.ProfileManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Sync profile display names + avatars via `user_profile_meta` (PlayTorrioV2). */
object CloudProfileMetaSync {
    private const val TAG = "CloudProfileMeta"
    private const val TABLE = "user_profile_meta"
    private const val PREFER_UPSERT = "return=minimal,resolution=merge-duplicates"
    private val jsonType = "application/json".toMediaType()

    suspend fun pullAll(ctx: Context, session: CloudSession) {
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) return
        val url = "${CloudConfig.supabaseUrl}/rest/v1/$TABLE?user_id=eq.$userId"
        val resp = SupabaseAuthClient.authorizedGet(ctx, url) ?: return
        if (!resp.code.toString().startsWith("2")) {
            Log.w(TAG, "pull meta ${resp.code}: ${resp.body.take(200)}")
            return
        }
        val arr = runCatching { JSONArray(resp.body) }.getOrNull() ?: return
        val profiles = ProfileManager.loadProfiles().toMutableList()
        var changed = false
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val profileId = row.optInt("profile_id", 0)
            if (profileId !in CloudProfileId.MIN_ID..CloudProfileId.MAX_ID) continue
            val idx = profileId - 1
            while (profiles.size <= idx && ProfileManager.canAddMore()) {
                val created = ProfileManager.create("Profile ${profiles.size + 1}", AvatarCatalog.default())
                if (created != null) profiles += created else break
            }
            if (idx >= profiles.size) continue
            val name = row.optString("name", "").trim()
            val avatarKey = row.optInt("avatar_key", -1)
            val avatarUrl = if (avatarKey in 0 until AvatarCatalog.all.size) {
                AvatarCatalog.all[avatarKey].url
            } else profiles[idx].imageUrl
            val updated = profiles[idx].copy(
                name = name.ifBlank { profiles[idx].name },
                imageUrl = avatarUrl ?: profiles[idx].imageUrl,
            )
            if (updated != profiles[idx]) {
                profiles[idx] = updated
                changed = true
            }
        }
        if (changed) {
            profiles.forEach { ProfileManager.upsert(it) }
        }
    }

    suspend fun pushActive(ctx: Context, session: CloudSession) {
        val profileId = CloudProfileId.activeProfileId()
        val profiles = ProfileManager.loadProfiles()
        val idx = profileId - 1
        val profile = profiles.getOrNull(idx) ?: ProfileManager.activeProfile()
        val avatarKey = profiles.indexOfFirst { it.id == profile.id }.let { i ->
            if (i >= 0) i.coerceAtMost(AvatarCatalog.all.size - 1) else 0
        }
        val avatarIdx = AvatarCatalog.all.indexOfFirst { it.url == profile.imageUrl }
            .takeIf { it >= 0 } ?: avatarKey

        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) throw IllegalStateException("Not signed in.")
        val body = JSONObject()
            .put("user_id", userId)
            .put("profile_id", profileId)
            .put("name", profile.name.take(32).ifBlank { "Profile $profileId" })
            .put("avatar_key", avatarIdx.coerceIn(0, 7))
            .toString()
        val url = "${CloudConfig.supabaseUrl}/rest/v1/$TABLE?on_conflict=user_id,profile_id"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonType))
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", PREFER_UPSERT)
            .build()
        val resp = SupabaseAuthClient.authorizedRequest(ctx, req)
            ?: throw IllegalStateException("Session expired.")
        if (!resp.code.toString().startsWith("2")) {
            throw IllegalStateException("Profile meta save failed (${resp.code}).")
        }
    }
}

package com.playtorrio.tv.data.cloud

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.playtorrio.tv.data.mylist.MyListStore
import com.playtorrio.tv.data.stremio.AddonManifest
import com.playtorrio.tv.data.stremio.InstalledAddon
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import com.playtorrio.tv.data.stremio.StremioAddonUrls
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Sync Stremio addons via PlayTorrioV2 `user_settings.prefs` → `stremio_addons`. */
object CloudUserSettingsSync {
    private const val TAG = "CloudUserSettings"
    private const val PREFS_STREMIO_ADDONS = "stremio_addons"
    private const val PREFS_MY_LIST = "my_list_items"
    private const val PREFER_UPSERT = "return=minimal,resolution=merge-duplicates"

    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    suspend fun pull(ctx: Context, session: CloudSession) {
        val profileId = CloudProfileId.activeProfileId()
        val prefs = fetchPrefs(ctx, session, profileId) ?: return
        val addons = parseAddonsFromPrefs(prefs)
        if (addons.isNotEmpty()) {
            StremioAddonRepository.replaceAllAddons(addons)
        }
        val myList = CloudMyListMerge.parseFromPrefsValue(prefs.opt(PREFS_MY_LIST))
        if (myList.isNotEmpty()) {
            val merged = CloudMyListMerge.merge(MyListStore.load(), myList)
            MyListStore.replaceAll(merged)
        }
    }

    suspend fun push(ctx: Context, session: CloudSession) {
        val profileId = CloudProfileId.activeProfileId()
        val existing = fetchPrefs(ctx, session, profileId) ?: JSONObject()
        val merged = JSONObject(existing.toString())
        merged.put(PREFS_STREMIO_ADDONS, addonsToJsonArray(StremioAddonRepository.getAddons()))
        merged.put(PREFS_MY_LIST, CloudMyListMerge.toJsonString(MyListStore.load()))
        upsertPrefs(ctx, session, profileId, merged)
    }

    private fun fetchPrefs(ctx: Context, session: CloudSession, profileId: Int): JSONObject? {
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) return null
        val url = "${CloudConfig.supabaseUrl}/rest/v1/${CloudConfig.USER_SETTINGS_TABLE}" +
            "?select=prefs&user_id=eq.$userId&profile_id=eq.$profileId"
        val resp = SupabaseAuthClient.authorizedGet(ctx, url) ?: return null
        if (!resp.code.toString().startsWith("2")) {
            Log.w(TAG, "pull prefs ${resp.code}: ${resp.body.take(200)}")
            return null
        }
        return runCatching {
            val arr = JSONArray(resp.body)
            arr.optJSONObject(0)?.optJSONObject("prefs")
        }.getOrNull()
    }

    private fun upsertPrefs(ctx: Context, session: CloudSession, profileId: Int, prefs: JSONObject) {
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) throw IllegalStateException("Not signed in.")
        val body = JSONObject()
            .put("user_id", userId)
            .put("profile_id", profileId)
            .put("prefs", prefs)
            .toString()
        val url = "${CloudConfig.supabaseUrl}/rest/v1/${CloudConfig.USER_SETTINGS_TABLE}" +
            "?on_conflict=user_id,profile_id"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonType))
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", PREFER_UPSERT)
            .build()
        val resp = SupabaseAuthClient.authorizedRequest(ctx, req) ?: return
        if (!resp.code.toString().startsWith("2")) {
            throw IllegalStateException("Cloud settings save failed (${resp.code}).")
        }
    }

    private fun parseAddonsFromPrefs(prefs: JSONObject): List<InstalledAddon> {
        val raw = prefs.opt(PREFS_STREMIO_ADDONS) ?: return emptyList()
        val arr = when (raw) {
            is JSONArray -> raw
            else -> return emptyList()
        }
        val out = mutableListOf<InstalledAddon>()
        for (i in 0 until arr.length()) {
            val entry = arr.opt(i) ?: continue
            val obj = when (entry) {
                is String -> runCatching { JSONObject(entry) }.getOrNull()
                is JSONObject -> entry
                else -> null
            } ?: continue
            val baseUrl = obj.optString("baseUrl").ifBlank { obj.optString("transportUrl") }
            if (baseUrl.isBlank()) continue
            val manifestJson = obj.optJSONObject("manifest") ?: continue
            val manifest = runCatching {
                gson.fromJson(manifestJson.toString(), AddonManifest::class.java)
            }.getOrNull() ?: continue
            if (manifest.id.isBlank()) continue
            val transport = StremioAddonUrls.normalizeTransportKey(baseUrl)
            out += InstalledAddon(transportUrl = transport, manifest = manifest)
        }
        return dedupeByManifestId(out)
    }

    private fun addonsToJsonArray(addons: List<InstalledAddon>): JSONArray {
        val arr = JSONArray()
        for (a in addons) {
            val o = JSONObject()
            o.put("baseUrl", a.transportUrl)
            o.put("manifest", JSONObject(gson.toJson(a.manifest)))
            o.put("name", a.manifest.name)
            if (!a.manifest.logo.isNullOrBlank()) o.put("icon", a.manifest.logo)
            arr.put(o)
        }
        return arr
    }

    private fun dedupeByManifestId(addons: List<InstalledAddon>): List<InstalledAddon> {
        val seen = HashSet<String>()
        return addons.filter { seen.add(it.manifest.id) }
    }
}

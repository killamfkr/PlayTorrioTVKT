package com.playtorrio.tv.data.iptv

import android.content.Context
import org.json.JSONArray

/** Per-portal favorite live stream ids (TV guide + Live TV browser). */
object IptvFavoritesStore {
    private const val PREFS = "iptv_favorites_store"

    fun loadIds(ctx: Context, portalKey: String): Set<String> {
        val raw = prefs(ctx).getString("fav_$portalKey", null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet(arr.length()) {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrDefault(emptySet())
    }

    fun saveIds(ctx: Context, portalKey: String, ids: Set<String>) {
        val arr = JSONArray()
        ids.sorted().forEach { arr.put(it) }
        prefs(ctx).edit().putString("fav_$portalKey", arr.toString()).apply()
    }

    fun toggleId(ctx: Context, portalKey: String, streamId: String): Set<String> {
        val cur = loadIds(ctx, portalKey).toMutableSet()
        if (!cur.add(streamId)) cur.remove(streamId)
        saveIds(ctx, portalKey, cur)
        return cur
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

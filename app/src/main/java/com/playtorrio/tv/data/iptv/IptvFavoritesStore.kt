package com.playtorrio.tv.data.iptv

import android.content.Context
import org.json.JSONArray

/**
 * Favorite live channel IDs per Xtream portal ([IptvAliveStore.portalKey]).
 */
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

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

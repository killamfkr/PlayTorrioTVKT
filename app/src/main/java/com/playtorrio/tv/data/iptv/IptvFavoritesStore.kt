package com.playtorrio.tv.data.iptv

import android.content.Context
import org.json.JSONArray

/**
 * Favorites for IPTV:
 * - **Live stream ids** per Xtream portal ([IptvAliveStore.portalKey]) — `fav_<portalKey>`.
 * - **Scraped portal** subscriptions — `scraped_portal_favorite_keys` (JSON array of portal keys).
 */
object IptvFavoritesStore {

    private const val PREFS = "iptv_favorites_store"
    private const val KEY_SCRAPED_PORTAL_FAVORITES = "scraped_portal_favorite_keys"

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

    /** Low-level read/write for stream favorites (e.g. merge without loading full set twice). */
    fun addStreamId(ctx: Context, portalKey: String, streamId: String) {
        val cur = loadIds(ctx, portalKey).toMutableSet()
        if (cur.add(streamId)) saveIds(ctx, portalKey, cur)
    }

    fun removeStreamId(ctx: Context, portalKey: String, streamId: String) {
        val cur = loadIds(ctx, portalKey).toMutableSet()
        if (cur.remove(streamId)) saveIds(ctx, portalKey, cur)
    }

    // ── Scraped / verified portal shortcuts (main IPTV list) ─────────────

    fun loadFavoritePortalKeys(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_SCRAPED_PORTAL_FAVORITES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildSet(arr.length()) { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptySet())
    }

    fun saveFavoritePortalKeys(ctx: Context, keys: Set<String>) {
        val arr = JSONArray()
        keys.sorted().forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_SCRAPED_PORTAL_FAVORITES, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

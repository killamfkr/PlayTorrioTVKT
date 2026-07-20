package com.playtorrio.tv.data.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk cache for parsed EPG guides. TTL defaults to 6 hours — most IPTV providers
 * refresh their guides a few times per day.
 */
object EpgStore {
    private const val PREFS = "iptv_epg_cache_v1"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun portalKey(portal: IptvPortal): String =
        "${portal.kind}|${portal.url}|${portal.username}".lowercase()

    fun load(ctx: Context, portal: IptvPortal): EpgGuide? {
        val key = portalKey(portal)
        val raw = prefs(ctx).getString(key, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val loadedAt = root.optLong("loadedAt", 0L)
            if (System.currentTimeMillis() - loadedAt > TTL_MS) return null

            val channels = mutableMapOf<String, EpgChannel>()
            root.optJSONObject("channels")?.let { obj ->
                obj.keys().forEach { id ->
                    val c = obj.getJSONObject(id)
                    channels[id] = EpgChannel(
                        id = id,
                        name = c.optString("name", id),
                        icon = c.optString("icon", ""),
                    )
                }
            }

            val programs = mutableMapOf<String, List<EpgProgram>>()
            root.optJSONObject("programs")?.let { obj ->
                obj.keys().forEach { chId ->
                    val arr = obj.getJSONArray(chId)
                    programs[chId] = (0 until arr.length()).map { i ->
                        val p = arr.getJSONObject(i)
                        EpgProgram(
                            channelId = chId,
                            title = p.optString("title"),
                            description = p.optString("description"),
                            startMs = p.optLong("startMs"),
                            endMs = p.optLong("endMs"),
                        )
                    }
                }
            }
            EpgGuide(channels, programs, loadedAt)
        }.getOrNull()
    }

    fun save(ctx: Context, portal: IptvPortal, guide: EpgGuide) {
        val key = portalKey(portal)
        val root = JSONObject().apply {
            put("loadedAt", guide.loadedAt)
            put("channels", JSONObject().apply {
                guide.channels.forEach { (id, ch) ->
                    put(id, JSONObject().apply {
                        put("name", ch.name)
                        put("icon", ch.icon)
                    })
                }
            })
            put("programs", JSONObject().apply {
                guide.programsByChannel.forEach { (chId, progs) ->
                    put(chId, JSONArray().apply {
                        progs.forEach { p ->
                            put(JSONObject().apply {
                                put("title", p.title)
                                put("description", p.description)
                                put("startMs", p.startMs)
                                put("endMs", p.endMs)
                            })
                        }
                    })
                }
            })
        }
        prefs(ctx).edit().putString(key, root.toString()).apply()
    }
}

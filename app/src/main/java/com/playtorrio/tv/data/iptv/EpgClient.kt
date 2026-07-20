package com.playtorrio.tv.data.iptv

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches EPG data from Xtream Codes (`xmltv.php`) or M3U playlist `url-tvg` headers,
 * then maps programmes onto live [IptvStream] entries.
 */
object EpgClient {
    private const val TAG = "EpgClient"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private const val UA = "VLC/3.0.20 LibVLC/3.0.20"

    /** Fetch and parse the full guide for a portal. Returns null on failure. */
    fun loadGuide(portal: IptvPortal, streams: List<IptvStream>): EpgGuide? {
        val raw = when (portal.kind) {
            "m3u" -> fetchM3uXmlTv(portal.url)
            else -> fetchXtreamXmlTv(portal)
        } ?: return buildShortEpgFallback(portal, streams)

        val guide = XmlTvParser.parse(raw)
        if (guide.programsByChannel.isEmpty()) {
            return buildShortEpgFallback(portal, streams) ?: guide
        }
        return guide
    }

    /** Build [GuideChannel] rows for UI, matching streams to EPG by id or name. */
    fun buildGuideChannels(
        streams: List<IptvStream>,
        guide: EpgGuide?,
        nowMs: Long = System.currentTimeMillis(),
    ): List<GuideChannel> {
        if (guide == null) {
            return streams.map { stream ->
                GuideChannel(
                    stream = stream,
                    epgId = stream.epgChannelId,
                    channelName = stream.name,
                    icon = stream.icon,
                    now = null,
                    next = null,
                )
            }
        }

        val epgIdsByName = guide.channels.values
            .groupBy { it.name.lowercase() }
            .mapValues { it.value.first().id }

        return streams.map { stream ->
            val epgId = resolveEpgId(stream, guide, epgIdsByName)
            val meta = guide.channels[epgId]
            GuideChannel(
                stream = stream,
                epgId = epgId,
                channelName = meta?.name?.takeIf { it.isNotBlank() } ?: stream.name,
                icon = stream.icon.ifBlank { meta?.icon.orEmpty() },
                now = if (epgId.isNotEmpty()) guide.nowProgram(epgId, nowMs) else null,
                next = if (epgId.isNotEmpty()) guide.nextProgram(epgId, nowMs) else null,
            )
        }.sortedBy { it.channelName.lowercase() }
    }

    private fun resolveEpgId(
        stream: IptvStream,
        guide: EpgGuide,
        epgIdsByName: Map<String, String>,
    ): String {
        val direct = stream.epgChannelId
        if (direct.isNotEmpty() && guide.programsByChannel.containsKey(direct)) return direct
        if (direct.isNotEmpty() && guide.channels.containsKey(direct)) return direct

        val byName = epgIdsByName[stream.name.lowercase()]
        if (byName != null) return byName

        // Fuzzy: EPG channel id often equals stream id for Xtream.
        if (guide.programsByChannel.containsKey(stream.streamId)) return stream.streamId
        return direct
    }

    private fun fetchXtreamXmlTv(portal: IptvPortal): String? {
        val url = "${portal.url}/xmltv.php?username=${enc(portal.username)}" +
            "&password=${enc(portal.password)}"
        return httpGet(url, 45_000)
    }

    private fun fetchM3uXmlTv(playlistUrl: String): String? {
        val header = M3uParser.getHeader(playlistUrl) ?: return null
        val tvgUrl = header.urlTvg?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return null
        return httpGet(tvgUrl, 45_000)
    }

    /** Per-stream short EPG via Xtream API — used when full XMLTV is unavailable. */
    private fun buildShortEpgFallback(
        portal: IptvPortal,
        streams: List<IptvStream>,
    ): EpgGuide? {
        if (portal.kind == "m3u") return null
        val live = streams.filter { it.kind == "live" }.take(60)
        if (live.isEmpty()) return null

        val channels = mutableMapOf<String, EpgChannel>()
        val programs = mutableMapOf<String, MutableList<EpgProgram>>()

        for (stream in live) {
            val epgId = stream.epgChannelId.ifBlank { stream.streamId }
            val items = fetchShortEpg(portal, stream.streamId) ?: continue
            channels[epgId] = EpgChannel(
                id = epgId,
                name = stream.name,
                icon = stream.icon,
            )
            programs[epgId] = items.map { item ->
                EpgProgram(
                    channelId = epgId,
                    title = item.title,
                    description = item.description,
                    startMs = item.startMs,
                    endMs = item.endMs,
                )
            }.toMutableList()
        }

        if (programs.isEmpty()) return null
        return EpgGuide(channels, programs)
    }

    private data class ShortEpgItem(
        val title: String,
        val description: String,
        val startMs: Long,
        val endMs: Long,
    )

    private fun fetchShortEpg(portal: IptvPortal, streamId: String): List<ShortEpgItem>? {
        val url = "${portal.url}/player_api.php?username=${enc(portal.username)}" +
            "&password=${enc(portal.password)}&action=get_short_epg&stream_id=${enc(streamId)}"
        val text = httpGet(url, 12_000) ?: return null
        return runCatching {
            val root = JSONObject(text)
            val arr = root.optJSONArray("epg_listings")
                ?: JSONArray(text) // some panels return a bare array
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").ifEmpty { o.optString("name") }
                if (title.isBlank()) return@mapNotNull null
                val start = o.optString("start_timestamp").toLongOrNull()
                    ?: XmlTvParser.parseXmlTvTime(o.optString("start"))
                val end = o.optString("stop_timestamp").toLongOrNull()
                    ?: XmlTvParser.parseXmlTvTime(o.optString("end"))
                    ?: XmlTvParser.parseXmlTvTime(o.optString("stop"))
                ShortEpgItem(
                    title = title,
                    description = o.optString("description").ifEmpty { o.optString("plot") },
                    startMs = if (start > 1_000_000_000_000L) start else start * 1000L,
                    endMs = if (end > 1_000_000_000_000L) end else end * 1000L,
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun httpGet(url: String, timeoutMs: Long): String? = try {
        val c = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/xml,text/xml,*/*")
            .build()
        c.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.v(TAG, "EPG GET ${resp.code}: $url")
                return null
            }
            resp.body?.string()
        }
    } catch (e: Exception) {
        Log.v(TAG, "EPG fail $url: ${e.message}")
        null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

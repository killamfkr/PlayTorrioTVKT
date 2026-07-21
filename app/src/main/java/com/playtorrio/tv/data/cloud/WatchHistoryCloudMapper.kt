package com.playtorrio.tv.data.cloud

import com.playtorrio.tv.data.watch.WatchKind
import com.playtorrio.tv.data.watch.WatchProgress
import org.json.JSONArray
import org.json.JSONObject

/** Maps TV [WatchProgress] ↔ PlayTorrioV2 mobile watch history entries. */
object WatchHistoryCloudMapper {

    private fun mobileUniqueId(wp: WatchProgress): String {
        if (wp.tmdbId > 0) {
            return if (!wp.isMovie && wp.seasonNumber != null && wp.episodeNumber != null) {
                "${wp.tmdbId}_S${wp.seasonNumber}_E${wp.episodeNumber}"
            } else {
                "${wp.tmdbId}"
            }
        }
        return wp.key
    }

    fun toMobileEntry(wp: WatchProgress): JSONObject {
        val method = when (wp.kind) {
            WatchKind.STREAMING -> "stream"
            WatchKind.MAGNET -> "torrent"
            WatchKind.ADDON_STREAM -> "stremio_direct"
            WatchKind.ANIME -> "anime"
        }
        return JSONObject().apply {
            put("uniqueId", mobileUniqueId(wp))
            put("tmdbId", wp.tmdbId)
            put("imdbId", wp.imdbId ?: JSONObject.NULL)
            put("title", wp.title)
            put(
                "posterPath",
                when {
                    !wp.posterUrl.isNullOrBlank() -> wp.posterUrl
                    !wp.backdropUrl.isNullOrBlank() -> wp.backdropUrl
                    else -> ""
                },
            )
            put("method", method)
            put("sourceId", wp.sourceIdForCloud())
            put("position", wp.positionMs.toInt())
            put("duration", wp.durationMs.toInt())
            put("season", wp.seasonNumber ?: JSONObject.NULL)
            put("episode", wp.episodeNumber ?: JSONObject.NULL)
            put("episodeTitle", wp.episodeTitle ?: JSONObject.NULL)
            put("magnetLink", wp.magnetUri ?: JSONObject.NULL)
            put("fileIndex", wp.fileIdx ?: JSONObject.NULL)
            put("stremioId", wp.stremioId ?: JSONObject.NULL)
            put("stremioAddonBaseUrl", wp.addonBaseUrlForCloud())
            put("stremioType", wp.stremioType ?: JSONObject.NULL)
            put("mediaType", if (wp.isMovie) "movie" else "tv")
            if (wp.kind == WatchKind.ADDON_STREAM && !wp.streamPickKey.isNullOrBlank()) {
                put("streamUrl", wp.streamPickKey)
            }
            put("updatedAt", wp.updatedAt)
        }
    }

    private fun WatchProgress.sourceIdForCloud(): String = when (kind) {
        WatchKind.MAGNET -> magnetUri ?: ""
        WatchKind.ADDON_STREAM -> streamPickKey ?: stremioId ?: ""
        WatchKind.ANIME -> streamUrl ?: animeId ?: ""
        WatchKind.STREAMING -> sourceIndex?.toString() ?: "0"
    }

    private fun WatchProgress.addonBaseUrlForCloud(): Any {
        val id = addonId ?: return JSONObject.NULL
        val match = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
            .firstOrNull { it.manifest.id == id }
        return match?.transportUrl ?: JSONObject.NULL
    }

    fun listToEntriesJsonArray(list: List<WatchProgress>): JSONArray {
        val arr = JSONArray()
        list.take(50).forEach { arr.put(toMobileEntry(it)) }
        return arr
    }

    fun updatedAtMs(o: JSONObject): Long {
        val v = o.opt("updatedAt") ?: return 0L
        return when (v) {
            is Number -> v.toLong()
            else -> v.toString().toLongOrNull() ?: 0L
        }
    }

    fun fromMobileEntry(o: JSONObject): WatchProgress? {
        return try {
            val uniqueId = o.optString("uniqueId", "")
            if (uniqueId.isEmpty()) return null
        val tmdbId = o.optInt("tmdbId", 0)
        val season = if (o.isNull("season")) null else o.optInt("season").takeIf { it > 0 }
        val episode = if (o.isNull("episode")) null else o.optInt("episode").takeIf { it > 0 }
        val mediaType = o.optString("mediaType", "")
        val isMovie = mediaType == "movie" ||
            (season == null && episode == null && uniqueId.matches(Regex("^\\d+$")))
        val baseUrl = o.optStringOrNull("stremioAddonBaseUrl")
        val resolvedAddonId = if (!baseUrl.isNullOrBlank()) {
            com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                .firstOrNull {
                    com.playtorrio.tv.data.stremio.StremioAddonUrls.normalizeTransportKey(it.transportUrl) ==
                        com.playtorrio.tv.data.stremio.StremioAddonUrls.normalizeTransportKey(baseUrl)
                }?.manifest?.id
        } else null

        val kindResolved = when {
            o.optString("method", "") == "torrent" -> WatchKind.MAGNET
            o.optString("method", "") == "stremio_direct" -> WatchKind.ADDON_STREAM
            o.optString("method", "") == "anime" -> WatchKind.ANIME
            tmdbId <= 0 && resolvedAddonId != null -> WatchKind.ADDON_STREAM
            else -> WatchKind.STREAMING
        }

        val stremioIdVal = o.optStringOrNull("stremioId")
        val stremioTypeVal = o.optStringOrNull("stremioType")
        val key = WatchProgress.makeKey(
            kind = kindResolved,
            tmdbId = tmdbId,
            isMovie = isMovie,
            seasonNumber = season,
            episodeNumber = episode,
            addonId = resolvedAddonId,
            stremioType = stremioTypeVal,
            stremioId = stremioIdVal,
            animeId = null,
        )
        WatchProgress(
            key = key,
            kind = kindResolved,
            tmdbId = tmdbId,
            imdbId = o.optStringOrNull("imdbId"),
            isMovie = isMovie,
            title = o.optString("title", ""),
            episodeTitle = o.optStringOrNull("episodeTitle"),
            seasonNumber = season,
            episodeNumber = episode,
            posterUrl = o.optStringOrNull("posterPath"),
            backdropUrl = null,
            logoUrl = null,
            year = null,
            rating = null,
            overview = null,
            sourceIndex = parseSourceIndex(o.opt("sourceId")),
            magnetUri = o.optStringOrNull("magnetLink"),
            fileIdx = if (o.isNull("fileIndex")) null else o.optInt("fileIndex"),
            addonId = resolvedAddonId,
            stremioType = stremioTypeVal,
            stremioId = stremioIdVal,
            streamPickKey = o.optStringOrNull("streamUrl") ?: o.optStringOrNull("sourceId"),
            streamPickName = null,
            positionMs = o.optLong("position", 0L),
            durationMs = o.optLong("duration", 0L),
            updatedAt = updatedAtMs(o),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSourceIndex(v: Any?): Int? = when (v) {
        null -> null
        is Number -> v.toInt()
        else -> v.toString().toIntOrNull()
    }

    private fun JSONObject.optStringOrNull(k: String): String? {
        if (isNull(k)) return null
        return optString(k, "").takeIf { it.isNotEmpty() }
    }
}

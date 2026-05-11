package com.playtorrio.tv.data.sync

import com.playtorrio.tv.data.watch.WatchKind
import com.playtorrio.tv.data.watch.WatchProgress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps TV [WatchProgress] ↔ PlayTorrio mobile `WatchHistoryService` entry maps
 * (see Flutter `watch_history_service.dart`) for `user_watch_history.entries`.
 */
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
        }
        val o = JSONObject()
        o.put("uniqueId", mobileUniqueId(wp))
        o.put("tmdbId", wp.tmdbId)
        o.put("imdbId", wp.imdbId ?: JSONObject.NULL)
        o.put("title", wp.title)
        o.put(
            "posterPath",
            when {
                !wp.posterUrl.isNullOrBlank() -> wp.posterUrl
                !wp.backdropUrl.isNullOrBlank() -> wp.backdropUrl
                else -> ""
            }
        )
        o.put("method", method)
        o.put("sourceId", wp.sourceIdForCloud())
        o.put("position", wp.positionMs.toInt())
        o.put("duration", wp.durationMs.toInt())
        o.put("season", wp.seasonNumber ?: JSONObject.NULL)
        o.put("episode", wp.episodeNumber ?: JSONObject.NULL)
        o.put("episodeTitle", wp.episodeTitle ?: JSONObject.NULL)
        o.put("magnetLink", wp.magnetUri ?: JSONObject.NULL)
        o.put("fileIndex", wp.fileIdx ?: JSONObject.NULL)
        o.put("stremioId", wp.stremioId ?: JSONObject.NULL)
        o.put("stremioAddonBaseUrl", wp.addonBaseUrlForCloud())
        o.put("stremioType", wp.stremioType ?: JSONObject.NULL)
        o.put("mediaType", if (wp.isMovie) "movie" else "tv")
        if (wp.kind == WatchKind.ADDON_STREAM && !wp.streamPickKey.isNullOrBlank()) {
            o.put("streamUrl", wp.streamPickKey)
        }
        o.put("updatedAt", wp.updatedAt)
        return o
    }

    private fun WatchProgress.sourceIdForCloud(): String {
        return when (kind) {
            WatchKind.MAGNET -> magnetUri ?: ""
            WatchKind.ADDON_STREAM -> streamPickKey ?: stremioId ?: ""
            WatchKind.STREAMING -> sourceIndex?.toString() ?: "0"
        }
    }

    private fun WatchProgress.addonBaseUrlForCloud(): Any {
        val id = addonId ?: return JSONObject.NULL
        val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
        val match = addons.firstOrNull { it.manifest.id == id }
        return match?.transportUrl ?: JSONObject.NULL
    }

    fun listToEntriesJsonArray(list: List<WatchProgress>): JSONArray {
        val arr = JSONArray()
        val bounded = list.take(50)
        for (wp in bounded) {
            arr.put(toMobileEntry(wp))
        }
        return arr
    }

    fun updatedAtMs(o: JSONObject): Long {
        val v = o.opt("updatedAt") ?: return 0L
        return when (v) {
            is Number -> v.toLong()
            else -> v.toString().toLongOrNull() ?: 0L
        }
    }

    /** Best-effort conversion from mobile history row to TV progress (may return null). */
    fun fromMobileEntry(o: JSONObject): WatchProgress? {
        return try {
            val uniqueId = o.optString("uniqueId", "")
            if (uniqueId.isEmpty()) {
                null
            } else {
                val tmdbId = o.optInt("tmdbId", 0)
                val season = if (o.isNull("season")) null else o.optInt("season").takeIf { it > 0 }
                val episode = if (o.isNull("episode")) null else o.optInt("episode").takeIf { it > 0 }
                val mediaType = o.optString("mediaType", "")
                val isMovie =
                    mediaType == "movie" ||
                        (season == null && episode == null && uniqueId.matches(Regex("^\\d+$")))
                val baseUrl = o.optStringOrNull("stremioAddonBaseUrl")
                val resolvedAddonId = if (!baseUrl.isNullOrBlank()) {
                    com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                        .firstOrNull {
                            com.playtorrio.tv.data.stremio.StremioAddonUrls.normalizeTransportKey(
                                it.transportUrl,
                            ) ==
                                com.playtorrio.tv.data.stremio.StremioAddonUrls.normalizeTransportKey(baseUrl)
                        }?.manifest?.id
                } else {
                    null
                }

                val kindResolved = when {
                    o.optString("method", "") == "torrent" -> WatchKind.MAGNET
                    o.optString("method", "") == "stremio_direct" -> WatchKind.ADDON_STREAM
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
                    stremioType = o.optStringOrNull("stremioType"),
                    stremioId = o.optStringOrNull("stremioId"),
                    streamPickKey = o.optStringOrNull("streamUrl") ?: o.optStringOrNull("sourceId"),
                    streamPickName = null,
                    positionMs = o.optLong("position", 0L),
                    durationMs = o.optLong("duration", 0L),
                    updatedAt = updatedAtMs(o),
                )
            }
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
        val s = optString(k, "")
        return s.takeIf { it.isNotEmpty() }
    }
}

package com.playtorrio.tv.data.mylist

import org.json.JSONObject

/** Bookmarked movie or TV show — same shape as PlayTorrioV2 `my_list_items`. */
data class MyListItem(
    val uniqueId: String,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val voteAverage: Double = 0.0,
    val releaseDate: String = "",
    val source: String = "tmdb",
    val stremioType: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val isMovie: Boolean
        get() = mediaType == "movie" || (mediaType != "tv" && mediaType != "series" && stremioType == "movie")

    fun posterUrl(): String? {
        val path = posterPath ?: return null
        return if (path.startsWith("http", ignoreCase = true)) path
        else "https://image.tmdb.org/t/p/w342$path"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uniqueId", uniqueId)
        put("tmdbId", tmdbId ?: JSONObject.NULL)
        put("imdbId", imdbId ?: JSONObject.NULL)
        put("title", title)
        put("posterPath", posterPath ?: "")
        put("mediaType", mediaType)
        put("voteAverage", voteAverage)
        put("releaseDate", releaseDate)
        put("source", source)
        put("stremioType", stremioType ?: JSONObject.NULL)
        put("addedAt", addedAt)
    }

    companion object {
        fun movieId(tmdbId: Int, mediaType: String): String = "tmdb_${mediaType}_$tmdbId"

        fun fromJson(o: JSONObject): MyListItem? {
            return try {
                val uniqueId = o.optString("uniqueId", "")
                if (uniqueId.isBlank()) return null
            MyListItem(
                uniqueId = uniqueId,
                tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId").takeIf { it > 0 },
                imdbId = o.optStringOrNull("imdbId"),
                title = o.optString("title", ""),
                posterPath = o.optStringOrNull("posterPath"),
                mediaType = o.optString("mediaType", "movie"),
                voteAverage = o.optDouble("voteAverage", 0.0),
                releaseDate = o.optString("releaseDate", ""),
                source = o.optString("source", "tmdb"),
                stremioType = o.optStringOrNull("stremioType"),
                addedAt = o.optLong("addedAt", 0L),
            )
            } catch (_: Exception) {
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
    }
}

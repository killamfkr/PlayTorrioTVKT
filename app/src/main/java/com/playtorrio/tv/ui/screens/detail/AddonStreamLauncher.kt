package com.playtorrio.tv.ui.screens.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.playtorrio.tv.PlayerActivity
import com.playtorrio.tv.data.stremio.StremioMeta
import com.playtorrio.tv.data.stremio.StremioService
import com.playtorrio.tv.data.stremio.StremioStream
import com.playtorrio.tv.data.stremio.StreamRoute

/**
 * Shared helpers for launching Stremio addon streams — used by the detail
 * screen, continue-watching splash, and auto-play.
 */
object AddonStreamLauncher {

    /** Pick the best stream for automatic playback (direct/torrent preferred). */
    fun pickBestStream(streams: List<StremioStream>): StremioStream? {
        if (streams.isEmpty()) return null
        val playable = streams.filter { stream ->
            when (StremioService.routeStream(stream)) {
                is StreamRoute.Torrent,
                is StreamRoute.DirectUrl -> true
                else -> false
            }
        }
        return playable.firstOrNull() ?: streams.firstOrNull()
    }

    /**
     * Build a [PlayerActivity] intent for a routed stream, or null if the
     * stream type cannot be played in-app.
     */
    fun buildPlayerIntent(
        context: Context,
        stream: StremioStream,
        meta: StremioMeta?,
        type: String,
        stremioId: String,
        resumePositionMs: Long = 0L,
    ): Intent? {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("title", meta?.name ?: stream.name ?: stream.title ?: "")
            putExtra("backdropUrl", meta?.background)
            putExtra("posterUrl", meta?.poster)
            putExtra("overview", meta?.description)
            putExtra("isMovie", type == "movie")
            stream.addonId?.let { putExtra("addonId", it) }
            putExtra("stremioType", type)
            putExtra("stremioId", stremioId)
            (stream.url ?: stream.infoHash)?.let { putExtra("streamPickKey", it) }
            (stream.name ?: stream.title)?.let { putExtra("streamPickName", it) }
            if (resumePositionMs > 0L) putExtra("resumePositionMs", resumePositionMs)
        }

        return when (val route = StremioService.routeStream(stream)) {
            is StreamRoute.Torrent -> {
                intent.putExtra("magnetUri", route.magnet)
                route.fileIdx?.let { intent.putExtra("fileIdx", it) }
                intent
            }
            is StreamRoute.DirectUrl -> {
                intent.putExtra("streamUrl", route.url)
                intent.putExtra("streamReferer", route.headers?.get("Referer") ?: "")
                intent
            }
            else -> null
        }
    }

    fun launchStream(
        context: Context,
        stream: StremioStream,
        meta: StremioMeta?,
        type: String,
        stremioId: String,
        resumePositionMs: Long = 0L,
    ): Boolean {
        val intent = buildPlayerIntent(context, stream, meta, type, stremioId, resumePositionMs)
            ?: return false
        context.startActivity(intent)
        return true
    }
}

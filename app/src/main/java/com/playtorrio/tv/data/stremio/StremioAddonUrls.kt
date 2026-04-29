package com.playtorrio.tv.data.stremio

/**
 * Stremio addon URLs may include query parameters (config tokens). Path segments must be
 * appended after the path but before the query string.
 */
object StremioAddonUrls {

    data class Split(
        val pathBase: String,
        val query: String?
    )

    /**
     * Splits e.g. `https://x.y/z?apikey=foo` into path base + query (without `?`).
     */
    fun splitTransportUrl(url: String): Split {
        val trimmed = url.trim()
        val q = trimmed.indexOf('?')
        return if (q >= 0) {
            Split(
                pathBase = trimmed.substring(0, q).trimEnd('/'),
                query = trimmed.substring(q + 1).takeIf { it.isNotBlank() }
            )
        } else {
            Split(pathBase = trimmed.trimEnd('/'), query = null)
        }
    }

    /** Appends [suffix] (must start with `/`) before any `?` query on the transport URL. */
    fun appendPath(transportUrl: String, suffix: String): String {
        val parts = splitTransportUrl(transportUrl)
        val base = parts.pathBase.trimEnd('/')
        val path = base + suffix
        return if (parts.query != null) "$path?${parts.query}" else path
    }

    fun configurePageUrl(transportUrl: String): String = appendPath(transportUrl, "/configure")

    /** Normalizes transport/base URLs for equality (matches mobile `baseUrl` handling). */
    fun normalizeTransportKey(url: String): String {
        var u = url.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) {
            u = "https://" + u.removePrefix("stremio://").removePrefix("Stremio://")
        }
        u = u.replace(Regex("/manifest\\.json$"), "").trimEnd('/')
        return u
    }
}

package com.playtorrio.tv.data.stremio

object StremioAddonUrls {
    fun normalizeTransportKey(url: String): String {
        var u = url.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) {
            u = "https://" + u.removePrefix("stremio://").removePrefix("Stremio://")
        }
        return u.replace(Regex("/manifest\\.json$"), "").trimEnd('/')
    }
}

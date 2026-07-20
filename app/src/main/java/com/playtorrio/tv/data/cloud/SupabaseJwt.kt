package com.playtorrio.tv.data.cloud

import android.util.Base64
import org.json.JSONObject

internal object SupabaseJwt {
    fun expUnixSeconds(jwt: String): Long? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        var seg = parts[1]
        val pad = seg.length % 4
        if (pad > 0) seg += "=".repeat(4 - pad)
        return runCatching {
            val json = JSONObject(String(Base64.decode(seg, Base64.URL_SAFE or Base64.NO_WRAP)))
            json.optLong("exp").takeIf { it > 0L }
        }.getOrNull()
    }

    fun isAccessExpired(jwt: String, leewaySeconds: Long = 60): Boolean {
        val exp = expUnixSeconds(jwt) ?: return true
        val now = System.currentTimeMillis() / 1000L
        return now >= exp - leewaySeconds
    }

    fun userIdFromJwt(jwt: String): String? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        var seg = parts[1]
        val pad = seg.length % 4
        if (pad > 0) seg += "=".repeat(4 - pad)
        return runCatching {
            JSONObject(String(Base64.decode(seg, Base64.URL_SAFE or Base64.NO_WRAP)))
                .optString("sub")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

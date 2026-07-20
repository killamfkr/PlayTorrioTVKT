package com.playtorrio.tv.data.cloud

import android.content.Context
import android.content.SharedPreferences

object CloudSessionStore {
    private const val PREFS = "playtorrio_cloud_session_v1"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): CloudSession? {
        val p = prefs(ctx)
        val access = p.getString("access_token", null) ?: return null
        val refresh = p.getString("refresh_token", null) ?: return null
        val userId = p.getString("user_id", null) ?: return null
        val email = p.getString("email", "").orEmpty()
        val expiresAt = p.getLong("expires_at_ms", 0L)
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) return null
        return CloudSession(
            accessToken = access,
            refreshToken = refresh,
            userId = userId,
            email = email,
            expiresAtMs = expiresAt,
        )
    }

    fun save(ctx: Context, session: CloudSession) {
        prefs(ctx).edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putLong("expires_at_ms", session.expiresAtMs)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun email(ctx: Context): String? = load(ctx)?.email
}

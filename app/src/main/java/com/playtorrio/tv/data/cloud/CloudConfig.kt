package com.playtorrio.tv.data.cloud

import com.playtorrio.tv.BuildConfig

object CloudConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()

    /** PostgREST table holding synced IPTV portal rows per user. */
    const val IPTV_PORTALS_TABLE = "iptv_portals"

    /** Optional profile row fallback when portals live on the user profile. */
    const val PROFILES_TABLE = "profiles"

    fun isConfigured(): Boolean =
        supabaseUrl.startsWith("http", ignoreCase = true) && supabaseAnonKey.isNotBlank()
}

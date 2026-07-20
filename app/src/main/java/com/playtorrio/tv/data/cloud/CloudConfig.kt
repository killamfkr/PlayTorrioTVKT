package com.playtorrio.tv.data.cloud

import com.playtorrio.tv.BuildConfig

/**
 * PlayTorrio Cloud (Supabase) — same project as [PlayTorrioV2](https://github.com/killamfkr/PlayTorrioV2).
 * Defaults match `lib/services/playtorrio_cloud_sync_service.dart`; override via `local.properties`.
 */
object CloudConfig {
    /** PlayTorrioV2 production Supabase project. */
    const val DEFAULT_SUPABASE_URL = "https://lxapazzlduwwecatebti.supabase.co"

    /** Legacy anon JWT from Project Settings → API (required for PostgREST). */
    const val DEFAULT_SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx4YXBhenpsZHV3d2VjYXRlYnRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzcyOTI2NDQsImV4cCI6MjA5Mjg2ODY0NH0.a9e7zUEdWDmf4Qor-rbYZ6G0sMTEYcfKnwTrXjVrBWY"

    val supabaseUrl: String =
        BuildConfig.SUPABASE_URL.trim().ifBlank { DEFAULT_SUPABASE_URL }.trimEnd('/')

    val supabaseAnonKey: String =
        BuildConfig.SUPABASE_ANON_KEY.trim().ifBlank { DEFAULT_SUPABASE_ANON_KEY }

    const val USER_SETTINGS_TABLE = "user_settings"

    /** Nested under `user_settings.prefs` — see PlayTorrioV2 `IptvCloudBundle`. */
    const val IPTV_CLOUD_BUNDLE_KEY = "iptv_pt_cloud_bundle_v1"

    /** Wrapped (`{"t":"s","v":"[...]"}`) verified portal list inside the IPTV bundle. */
    const val VERIFIED_PORTALS_KEY = "pt_iptv_verified_portals"

    fun isConfigured(): Boolean =
        supabaseUrl.startsWith("http", ignoreCase = true) && supabaseAnonKey.isNotBlank()

    fun isAnonKeyJwtFormat(): Boolean {
        val k = supabaseAnonKey
        return k.split('.').size == 3 && k.startsWith("eyJ")
    }
}

package com.playtorrio.tv.data

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    /** Same sentinel as PlayTorrio mobile [SettingsService.streamSourceForcePlayTorrio]. */
    const val DEFAULT_STREAM_FORCE_PLAYTORRIO = "__pref_playtorrio__"

    private const val PREFS_BASE = "playtorrio_prefs"
    private const val KEY_STREAMING_MODE = "streaming_mode"
    private const val KEY_DEBRID_ENABLED = "debrid_enabled"
    private const val KEY_DEBRID_PROVIDER = "debrid_provider"
    private const val KEY_REALDEBRID_API_KEY = "realdebrid_api_key"
    private const val KEY_TORBOX_API_KEY = "torbox_api_key"
    private const val KEY_TORRENT_PRESET = "torrent_preset"
    private const val KEY_TORRENT_CACHE_MB = "torrent_cache_mb"
    private const val KEY_TORRENT_PRELOAD = "torrent_preload"
    private const val KEY_TORRENT_READ_AHEAD = "torrent_read_ahead"
    private const val KEY_TORRENT_CONNECTIONS = "torrent_connections"
    private const val KEY_TORRENT_RESPONSIVE = "torrent_responsive"
    private const val KEY_TORRENT_DISABLE_UPLOAD = "torrent_disable_upload"
    private const val KEY_TORRENT_DISABLE_IPV6 = "torrent_disable_ipv6"
    private const val KEY_TORRENT_AUTO_PICK = "torrent_auto_picker_mode"

    /** Manual torrent list (default). */
    const val TORRENT_AUTO_PICK_MANUAL = "manual"
    /** Pick best result from built-in PlayTorrio torrent search. */
    const val TORRENT_AUTO_PICK_PLAYTORRIO = "playtorrio"
    /** Pick first torrent stream from installed Stremio add-ons (respects default stream source). */
    const val TORRENT_AUTO_PICK_ADDON = "addon"
    private const val KEY_TRAILER_AUTOPLAY = "trailer_autoplay"
    private const val KEY_TRAILER_DELAY_SEC = "trailer_delay_sec"
    private const val KEY_SAVED_ALBUM_IDS = "saved_album_ids"
    private const val KEY_SAVED_TRACK_IDS = "saved_track_ids"
    private const val KEY_MUSIC_PLAYLISTS = "music_playlists"
    private const val KEY_SAVED_AUDIOBOOKS = "saved_audiobooks_v1"
    private const val KEY_AUDIOBOOK_PROGRESS = "audiobook_progress_v1"
    private const val KEY_WATCH_PROGRESS = "watch_progress_v1"
    private const val KEY_DEFAULT_STREMIO_TRANSPORT = "default_stremio_transport_url"
    private const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
    private const val KEY_SUPABASE_REFRESH_TOKEN = "supabase_refresh_token"
    private const val KEY_SUPABASE_USER_ID = "supabase_user_id"
    private const val KEY_SUPABASE_EMAIL = "supabase_email"
    /** One library file for all devices (matches PlayTorrio mobile account sync). */
    private const val KEY_MIGRATED_SINGLE_LIBRARY = "_migrated_single_library_prefs_v1"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_BASE, Context.MODE_PRIVATE)
        migrateLegacyProfilePrefs(context)
        com.playtorrio.tv.data.stremio.StremioAddonRepository.init(context)
    }

    private fun migrateLegacyProfilePrefs(context: Context) {
        if (prefs.getBoolean(KEY_MIGRATED_SINGLE_LIBRARY, false)) return
        val activeId = try {
            com.playtorrio.tv.data.profile.ProfileManager.activeId()
        } catch (_: Exception) {
            "default"
        }
        val legacyName = if (activeId == "default") PREFS_BASE else "${PREFS_BASE}_$activeId"
        if (legacyName != PREFS_BASE) {
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                val ed = prefs.edit()
                copySharedPreferencesInto(legacy, ed)
                ed.apply()
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED_SINGLE_LIBRARY, true).apply()
    }

    private fun copySharedPreferencesInto(from: SharedPreferences, to: SharedPreferences.Editor) {
        for ((k, v) in from.all) {
            when (v) {
                is String -> to.putString(k, v)
                is Boolean -> to.putBoolean(k, v)
                is Int -> to.putInt(k, v)
                is Long -> to.putLong(k, v)
                is Float -> to.putFloat(k, v)
                is Set<*> -> @Suppress("UNCHECKED_CAST") to.putStringSet(k, v as Set<String>)
                null -> {}
            }
        }
    }

    var streamingMode: Boolean
        get() = prefs.getBoolean(KEY_STREAMING_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_STREAMING_MODE, value).apply()

    var debridEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBRID_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBRID_ENABLED, value).apply()

    /** "realdebrid" or "torbox" */
    var debridProvider: String
        get() = prefs.getString(KEY_DEBRID_PROVIDER, "realdebrid") ?: "realdebrid"
        set(value) = prefs.edit().putString(KEY_DEBRID_PROVIDER, value).apply()

    var realDebridApiKey: String
        get() = prefs.getString(KEY_REALDEBRID_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REALDEBRID_API_KEY, value).apply()

    var torboxApiKey: String
        get() = prefs.getString(KEY_TORBOX_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TORBOX_API_KEY, value).apply()

    var torrentPreset: String
        get() = prefs.getString(KEY_TORRENT_PRESET, "balanced") ?: "balanced"
        set(value) = prefs.edit().putString(KEY_TORRENT_PRESET, value).apply()

    var torrentCacheSizeMb: Int
        get() = prefs.getInt(KEY_TORRENT_CACHE_MB, 256)
        set(value) = prefs.edit().putInt(KEY_TORRENT_CACHE_MB, value).apply()

    var torrentPreloadPercent: Int
        get() = prefs.getInt(KEY_TORRENT_PRELOAD, 1)
        set(value) = prefs.edit().putInt(KEY_TORRENT_PRELOAD, value).apply()

    var torrentReadAheadPercent: Int
        get() = prefs.getInt(KEY_TORRENT_READ_AHEAD, 86)
        set(value) = prefs.edit().putInt(KEY_TORRENT_READ_AHEAD, value).apply()

    var torrentConnectionsLimit: Int
        get() = prefs.getInt(KEY_TORRENT_CONNECTIONS, 140)
        set(value) = prefs.edit().putInt(KEY_TORRENT_CONNECTIONS, value).apply()

    var torrentResponsiveMode: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_RESPONSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_RESPONSIVE, value).apply()

    var torrentDisableUpload: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_DISABLE_UPLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_DISABLE_UPLOAD, value).apply()

    var torrentDisableIpv6: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_DISABLE_IPV6, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_DISABLE_IPV6, value).apply()

    /**
     * When opening the torrent picker from TMDB detail: [TORRENT_AUTO_PICK_MANUAL] shows the list;
     * [TORRENT_AUTO_PICK_PLAYTORRIO] / [TORRENT_AUTO_PICK_ADDON] start playback automatically when a pick exists.
     */
    var torrentAutoPickerMode: String
        get() = prefs.getString(KEY_TORRENT_AUTO_PICK, TORRENT_AUTO_PICK_MANUAL) ?: TORRENT_AUTO_PICK_MANUAL
        set(value) {
            val v = when (value) {
                TORRENT_AUTO_PICK_PLAYTORRIO,
                TORRENT_AUTO_PICK_ADDON,
                -> value
                else -> TORRENT_AUTO_PICK_MANUAL
            }
            prefs.edit().putString(KEY_TORRENT_AUTO_PICK, v).apply()
        }

    var trailerAutoplay: Boolean
        get() = prefs.getBoolean(KEY_TRAILER_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_TRAILER_AUTOPLAY, value).apply()

    /** Seconds to wait before auto-playing trailer (3–10) */
    var trailerDelaySec: Int
        get() = prefs.getInt(KEY_TRAILER_DELAY_SEC, 3)
        set(value) = prefs.edit().putInt(KEY_TRAILER_DELAY_SEC, value.coerceIn(3, 10)).apply()

    var savedAlbumIds: Set<String>
        get() = prefs.getStringSet(KEY_SAVED_ALBUM_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SAVED_ALBUM_IDS, value).apply()

    var savedTrackIds: Set<String>
        get() = prefs.getStringSet(KEY_SAVED_TRACK_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SAVED_TRACK_IDS, value).apply()

    /** JSON-encoded list of playlists: [{"name":"x","trackIds":["1","2"]}] */
    var musicPlaylists: String
        get() = prefs.getString(KEY_MUSIC_PLAYLISTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_MUSIC_PLAYLISTS, value).apply()

    /** JSON-encoded list of saved AudiobookDetail objects (one entry per liked book). */
    var savedAudiobooks: String
        get() = prefs.getString(KEY_SAVED_AUDIOBOOKS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_SAVED_AUDIOBOOKS, value).apply()

    /** JSON-encoded list of AudiobookProgress entries (continue-listening). */
    var audiobookProgress: String
        get() = prefs.getString(KEY_AUDIOBOOK_PROGRESS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_AUDIOBOOK_PROGRESS, value).apply()

    /** JSON-encoded list of WatchProgress entries (continue-watching for movies/TV). */
    var watchProgress: String
        get() = prefs.getString(KEY_WATCH_PROGRESS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_WATCH_PROGRESS, value).apply()

    /**
     * Preferred Stremio addon for streams when multiple addons apply (matches mobile
     * `default_stremio_addon_base_url` using transport/base URL without `/manifest.json`).
     * Empty = automatic first-match behavior.
     */
    var defaultStremioTransportUrl: String
        get() = prefs.getString(KEY_DEFAULT_STREMIO_TRANSPORT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_STREMIO_TRANSPORT, value.trim()).apply()

    var supabaseAccessToken: String
        get() = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_ACCESS_TOKEN, value).apply()

    var supabaseRefreshToken: String
        get() = prefs.getString(KEY_SUPABASE_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_REFRESH_TOKEN, value).apply()

    var supabaseUserId: String
        get() = prefs.getString(KEY_SUPABASE_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_USER_ID, value).apply()

    var supabaseEmail: String
        get() = prefs.getString(KEY_SUPABASE_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_EMAIL, value).apply()

    fun clearSupabaseSession() {
        prefs.edit()
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_SUPABASE_REFRESH_TOKEN)
            .remove(KEY_SUPABASE_USER_ID)
            .remove(KEY_SUPABASE_EMAIL)
            .apply()
    }

    fun applyTorrentPreset(preset: String) {
        when (preset.lowercase()) {
            "safe" -> {
                torrentPreset = "safe"
                torrentCacheSizeMb = 128
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 78
                torrentConnectionsLimit = 70
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "balanced" -> {
                torrentPreset = "balanced"
                torrentCacheSizeMb = 256
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 86
                torrentConnectionsLimit = 140
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "turbo" -> {
                torrentPreset = "turbo"
                torrentCacheSizeMb = 384
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 92
                torrentConnectionsLimit = 260
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "extreme" -> {
                torrentPreset = "extreme"
                torrentCacheSizeMb = 512
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 94
                torrentConnectionsLimit = 400
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            else -> torrentPreset = "custom"
        }
    }
}

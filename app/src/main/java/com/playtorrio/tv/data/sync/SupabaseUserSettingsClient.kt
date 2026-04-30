package com.playtorrio.tv.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.playtorrio.tv.data.stremio.AddonManifest
import com.playtorrio.tv.data.stremio.InstalledAddon
import com.playtorrio.tv.data.stremio.ResourceDescriptorDeserializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Syncs Stremio addon list and default stream source with Supabase so the TV app matches
 * [PlayTorrioV2](https://github.com/killamfkr/PlayTorrioV2) on mobile.
 *
 * **Database setup** (run in the Supabase SQL editor):
 *
 * ```
 * create table if not exists public.user_settings (
 *   user_id uuid primary key references auth.users (id) on delete cascade,
 *   stremio_addons jsonb not null default '[]'::jsonb,
 *   default_stream_source text,
 *   updated_at timestamptz default now()
 * );
 * alter table public.user_settings enable row level security;
 * create policy "user_settings_own_row"
 *   on public.user_settings for all
 *   using (auth.uid() = user_id)
 *   with check (auth.uid() = user_id);
 * ```
 *
 * If the table already existed **without** `stremio_addons`, run the migration in
 * `supabase/user_settings_columns.sql` (add column … if not exists).
 *
 * The mobile app stores each addon as `{ "baseUrl", "manifest", ... }`; we persist the same
 * JSON shape so rows round-trip.
 */
class SupabaseUserSettingsClient(
    supabaseUrl: String,
    private val anonKey: String
) {
    private val base = supabaseUrl.trimEnd('/')

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(
            com.playtorrio.tv.data.stremio.ResourceDescriptor::class.java,
            ResourceDescriptorDeserializer()
        )
        .create()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        val user: AuthUser?
    )

    private data class AuthUser(val id: String?, val email: String?)

    data class Session(
        val userId: String,
        val email: String?,
        val accessToken: String,
        val refreshToken: String?
    )

    suspend fun signInWithPassword(email: String, password: String): Session =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(
                mapOf(
                    "email" to email.trim(),
                    "password" to password,
                    "gotrue_meta_security" to emptyMap<String, String>()
                )
            )
            val req = Request.Builder()
                .url("$base/auth/v1/token?grant_type=password")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw SupabaseSyncException(parseAuthError(text) ?: "Sign-in failed (${resp.code})")
                }
                val tr = gson.fromJson(text, TokenResponse::class.java)
                    ?: throw SupabaseSyncException("Invalid auth response")
                val uid = tr.user?.id ?: throw SupabaseSyncException("Missing user id")
                val token = tr.accessToken ?: throw SupabaseSyncException("Missing access token")
                Session(
                    userId = uid,
                    email = tr.user.email,
                    accessToken = token,
                    refreshToken = tr.refreshToken
                )
            }
        }

    suspend fun refreshSession(refreshToken: String): Session =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(mapOf("refresh_token" to refreshToken))
            val req = Request.Builder()
                .url("$base/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw SupabaseSyncException(parseAuthError(text) ?: "Refresh failed (${resp.code})")
                }
                val tr = gson.fromJson(text, TokenResponse::class.java)
                    ?: throw SupabaseSyncException("Invalid refresh response")
                val uid = tr.user?.id ?: throw SupabaseSyncException("Missing user id")
                val token = tr.accessToken ?: throw SupabaseSyncException("Missing access token")
                Session(
                    userId = uid,
                    email = tr.user.email,
                    accessToken = token,
                    refreshToken = tr.refreshToken ?: refreshToken
                )
            }
        }

    suspend fun fetchUserSettings(accessToken: String, userId: String): UserSettingsRow? =
        withContext(Dispatchers.IO) {
            // Use select=* so we do not depend on PostgREST validating a fixed column list
            // (also avoids stale cache rejecting renamed projections). Parse optional keys below.
            val url =
                "$base/rest/v1/user_settings?user_id=eq.$userId&select=*"
            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw SupabaseSyncException(
                        formatLoadError(resp.code, text)
                    )
                }
                val arr = gson.fromJson(text, JsonArray::class.java)
                    ?: return@withContext null
                if (arr.size() == 0) return@withContext null
                val row = arr[0].asJsonObject
                val addonsEl = row.get("stremio_addons")
                val defaultSrc = row.get("default_stream_source")?.takeUnless { it.isJsonNull }?.asString
                val addonsJson = if (addonsEl != null && !addonsEl.isJsonNull) addonsEl.toString() else "[]"
                UserSettingsRow(stremioAddonsJson = addonsJson, defaultStreamSource = defaultSrc)
            }
        }

    private fun formatLoadError(code: Int, body: String): String {
        val hint =
            "Run supabase/user_settings_columns.sql in the SQL Editor (adds columns + reloads API schema). " +
                "Confirm the project URL matches this app."
        return when {
            body.contains("\"42703\"") ||
                body.contains("42703", ignoreCase = true) ||
                (body.contains("stremio_addons", ignoreCase = true) &&
                    body.contains("does not exist", ignoreCase = true)) ->
                "Database missing stremio_addons column. $hint Raw: $code $body"

            else -> "Load settings failed: $code $body"
        }
    }

    suspend fun upsertUserSettings(
        accessToken: String,
        userId: String,
        stremioAddonsJson: String,
        defaultStreamSource: String?
    ) = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("user_id", userId)
            add("stremio_addons", gson.fromJson(stremioAddonsJson, JsonArray::class.java)
                ?: JsonArray())
            if (defaultStreamSource != null) {
                addProperty("default_stream_source", defaultStreamSource)
            } else {
                add("default_stream_source", com.google.gson.JsonNull.INSTANCE)
            }
        }
        val req = Request.Builder()
            .url("$base/rest/v1/user_settings")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates")
            .post(gson.toJson(payload).toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw SupabaseSyncException(
                    if (text.contains("42703") ||
                        (text.contains("stremio_addons", ignoreCase = true) &&
                            text.contains("does not exist", ignoreCase = true))
                    ) {
                        "Database missing stremio_addons column. " +
                            "Run supabase/user_settings_columns.sql in the SQL Editor. Raw: ${resp.code} $text"
                    } else {
                        "Save failed: ${resp.code} $text"
                    }
                )
            }
        }
    }

    /**
     * Parses mobile-shaped addon entries into [InstalledAddon] for the TV Stremio stack.
     */
    fun parseInstalledAddonsFromMobileJson(json: String): List<InstalledAddon> {
        val arr = try {
            gson.fromJson(json, JsonArray::class.java)
        } catch (_: Exception) {
            JsonArray()
        } ?: JsonArray()
        val out = ArrayList<InstalledAddon>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val baseUrl = o.get("baseUrl")?.asString?.trim() ?: continue
            val manifestEl = o.get("manifest") ?: continue
            val manifest = try {
                gson.fromJson(manifestEl, AddonManifest::class.java)
            } catch (_: Exception) {
                null
            } ?: continue
            if (manifest.id.isBlank()) continue
            val transport = normalizeTransportFromBaseUrl(baseUrl)
            out += InstalledAddon(transportUrl = transport, manifest = manifest)
        }
        return dedupeByManifestId(out)
    }

    fun installedAddonsToMobileJson(addons: List<InstalledAddon>): String {
        val arr = JsonArray()
        for (a in addons) {
            val o = JsonObject()
            o.addProperty("baseUrl", a.transportUrl)
            o.add("manifest", gson.toJsonTree(a.manifest))
            o.addProperty("name", a.manifest.name)
            if (!a.manifest.logo.isNullOrBlank()) {
                o.addProperty("icon", a.manifest.logo)
            }
            arr.add(o)
        }
        return gson.toJson(arr)
    }

    private fun normalizeTransportFromBaseUrl(baseUrl: String): String {
        var u = baseUrl.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) {
            u = "https://" + u.removePrefix("stremio://").removePrefix("Stremio://")
        }
        u = u.replace(Regex("/manifest\\.json$"), "").trimEnd('/')
        return u
    }

    private fun dedupeByManifestId(addons: List<InstalledAddon>): List<InstalledAddon> {
        val seen = HashSet<String>()
        val result = ArrayList<InstalledAddon>()
        for (a in addons) {
            val id = a.manifest.id
            if (id in seen) continue
            seen.add(id)
            result.add(a)
        }
        return result
    }

    private fun parseAuthError(json: String): String? = try {
        val o = gson.fromJson(json, JsonObject::class.java) ?: return null
        val msg = o.get("msg_description")?.asString
            ?: o.get("error_description")?.asString
            ?: o.get("message")?.asString
        msg?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    data class UserSettingsRow(
        val stremioAddonsJson: String,
        val defaultStreamSource: String?
    )
}

class SupabaseSyncException(message: String) : Exception(message)

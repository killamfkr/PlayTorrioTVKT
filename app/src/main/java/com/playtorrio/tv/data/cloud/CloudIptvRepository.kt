package com.playtorrio.tv.data.cloud

import android.content.Context
import android.util.Log
import com.playtorrio.tv.data.iptv.IptvClient
import com.playtorrio.tv.data.iptv.IptvPortal
import com.playtorrio.tv.data.iptv.IptvStore
import com.playtorrio.tv.data.iptv.VerifiedPortal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pull IPTV portal credentials from PlayTorrioV2 cloud (`user_settings.prefs`
 * → `iptv_pt_cloud_bundle_v1` → `pt_iptv_verified_portals`) and verify locally.
 */
object CloudIptvRepository {
    private const val TAG = "CloudIptvRepo"

    suspend fun loginAndSync(
        ctx: Context,
        email: String,
        password: String,
    ): Result<CloudSyncResult> {
        val auth = SupabaseAuthClient.signIn(email, password).getOrElse { return Result.failure(it) }
        CloudSessionStore.save(ctx, auth)
        return syncPortals(ctx, auth)
    }

    suspend fun syncWithStoredSession(ctx: Context): Result<CloudSyncResult> {
        val session = SupabaseAuthClient.getValidSession(ctx)
            ?: return Result.failure(IllegalStateException("Not signed in to PlayTorrio Cloud."))
        return syncPortals(ctx, session)
    }

    fun signOut(ctx: Context) {
        SupabaseAuthClient.signOut(ctx)
    }

    fun isSignedIn(ctx: Context): Boolean = SupabaseAuthClient.hasStoredSession(ctx)

    fun signedInEmail(ctx: Context): String? = CloudSessionStore.email(ctx)

    private suspend fun syncPortals(
        ctx: Context,
        session: CloudSession,
    ): Result<CloudSyncResult> = runCatching {
        val profileId = CloudProfileId.activeProfileId()
        val rows = fetchPortalRows(ctx, session, profileId)
        if (rows.isEmpty()) {
            throw IllegalStateException(
                "No IPTV portals found on your PlayTorrio Cloud account (profile $profileId). " +
                    "Add portals in PlayTorrio and enable settings sync.",
            )
        }

        val verified = mutableListOf<VerifiedPortal>()
        val failures = mutableListOf<String>()

        for (row in rows) {
            val portal = IptvPortal(
                url = row.url,
                username = row.username,
                password = row.password,
                source = row.source,
                kind = row.kind.ifBlank { "xtream" },
            )
            val vp = runCatching { IptvClient.verifyOrNull(portal, timeoutMs = 10_000) }.getOrNull()
            if (vp != null) {
                val named = if (row.displayName.isNotBlank()) {
                    vp.copy(name = row.displayName)
                } else vp
                verified += named
            } else {
                failures += row.displayName.ifBlank { row.url }
            }
        }

        if (verified.isEmpty()) {
            throw IllegalStateException(
                "Found ${rows.size} cloud portal(s) but none could connect. Check your subscription.",
            )
        }

        val existing = IptvStore.load(ctx)
        val merged = (existing + verified).distinctBy {
            "${it.portal.url}|${it.portal.username}|${it.portal.password}".lowercase()
        }
        IptvStore.save(ctx, merged)

        CloudSyncResult(
            email = session.email,
            imported = verified.size,
            totalSaved = merged.size,
            failed = failures,
            profileId = profileId,
        )
    }

    private fun fetchPortalRows(
        ctx: Context,
        session: CloudSession,
        profileId: Int,
    ): List<CloudPortalRow> {
        val userId = session.userId.ifBlank { SupabaseJwt.userIdFromJwt(session.accessToken).orEmpty() }
        if (userId.isBlank()) return emptyList()

        val url = "${CloudConfig.supabaseUrl}/rest/v1/${CloudConfig.USER_SETTINGS_TABLE}" +
            "?select=prefs&user_id=eq.$userId&profile_id=eq.$profileId"
        val resp = SupabaseAuthClient.authorizedGet(ctx, url) ?: return emptyList()
        if (!resp.code.toString().startsWith("2")) {
            Log.w(TAG, "user_settings GET ${resp.code}: ${resp.body.take(200)}")
            return emptyList()
        }

        return runCatching {
            val arr = JSONArray(resp.body)
            val prefs = arr.optJSONObject(0)?.optJSONObject("prefs") ?: return emptyList()
            parseVerifiedPortalsFromPrefs(prefs)
        }.getOrElse {
            Log.w(TAG, "user_settings parse failed: ${it.message}")
            emptyList()
        }
    }

    /** PlayTorrioV2 wraps IPTV prefs inside `iptv_pt_cloud_bundle_v1`. */
    private fun parseVerifiedPortalsFromPrefs(prefs: JSONObject): List<CloudPortalRow> {
        val bundle = prefs.optJSONObject(CloudConfig.IPTV_CLOUD_BUNDLE_KEY) ?: return emptyList()
        val wrapped = bundle.optJSONObject(CloudConfig.VERIFIED_PORTALS_KEY) ?: return emptyList()
        if (wrapped.optString("t") != "s") return emptyList()
        val raw = wrapped.optString("v")
        if (raw.isBlank()) return emptyList()
        return parseVerifiedPortalsJson(raw)
    }

    /** V2 `IptvStore` JSON: url, username, password, source, name, expiry, max, active. */
    private fun parseVerifiedPortalsJson(raw: String): List<CloudPortalRow> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val portalUrl = o.optString("url")
            val user = o.optString("username")
            if (portalUrl.isBlank() || user.isBlank()) return@mapNotNull null
            val password = o.optString("password")
            CloudPortalRow(
                url = portalUrl,
                username = user,
                password = password,
                kind = inferKind(portalUrl, password, o.optString("kind")),
                displayName = o.optString("name"),
                source = o.optString("source", "PlayTorrio Cloud"),
            )
        }
    }

    private fun inferKind(url: String, password: String, explicit: String): String {
        if (explicit.isNotBlank()) return explicit
        val lower = url.lowercase()
        if (password.isBlank() && (lower.contains(".m3u") || lower.contains("m3u8") || lower.contains("/get.php"))) {
            return "m3u"
        }
        return "xtream"
    }
}

data class CloudSyncResult(
    val email: String,
    val imported: Int,
    val totalSaved: Int,
    val failed: List<String> = emptyList(),
    val profileId: Int = 1,
)

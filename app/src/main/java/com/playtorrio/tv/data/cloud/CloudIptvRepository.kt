package com.playtorrio.tv.data.cloud

import android.content.Context
import android.util.Log
import com.playtorrio.tv.data.iptv.IptvClient
import com.playtorrio.tv.data.iptv.IptvPortal
import com.playtorrio.tv.data.iptv.IptvStore
import com.playtorrio.tv.data.iptv.VerifiedPortal
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pull IPTV portal credentials from the user's PlayTorrio Cloud (Supabase) account
 * and verify them locally before saving to [IptvStore].
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
        CloudSessionStore.clear(ctx)
    }

    fun isSignedIn(ctx: Context): Boolean = CloudSessionStore.load(ctx) != null

    fun signedInEmail(ctx: Context): String? = CloudSessionStore.email(ctx)

    private suspend fun syncPortals(
        ctx: Context,
        session: CloudSession,
    ): Result<CloudSyncResult> = runCatching {
        val rows = fetchPortalRows(session)
        if (rows.isEmpty()) {
            throw IllegalStateException("No IPTV portals found on your PlayTorrio Cloud account.")
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
        )
    }

    private fun fetchPortalRows(session: CloudSession): List<CloudPortalRow> {
        val fromTable = fetchFromPortalsTable(session)
        if (fromTable.isNotEmpty()) return fromTable
        return fetchFromProfile(session)
    }

    private fun fetchFromPortalsTable(session: CloudSession): List<CloudPortalRow> {
        val url = "${CloudConfig.supabaseUrl}/rest/v1/${CloudConfig.IPTV_PORTALS_TABLE}" +
            "?select=url,username,password,kind,display_name,source"
        val text = authorizedGet(url, session) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val portalUrl = o.optString("url")
                val user = o.optString("username")
                if (portalUrl.isBlank() || user.isBlank()) return@mapNotNull null
                CloudPortalRow(
                    url = portalUrl,
                    username = user,
                    password = o.optString("password"),
                    kind = o.optString("kind", "xtream"),
                    displayName = o.optString("display_name"),
                    source = o.optString("source", "PlayTorrio Cloud"),
                )
            }
        }.getOrElse {
            Log.w(TAG, "iptv_portals parse failed: ${it.message}")
            emptyList()
        }
    }

    /** Fallback for single-portal accounts stored on the user profile row. */
    private fun fetchFromProfile(session: CloudSession): List<CloudPortalRow> {
        val url = "${CloudConfig.supabaseUrl}/rest/v1/${CloudConfig.PROFILES_TABLE}" +
            "?id=eq.${session.userId}&select=iptv_url,iptv_username,iptv_password,iptv_kind,iptv_name"
        val text = authorizedGet(url, session) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            val o = arr.optJSONObject(0) ?: return emptyList()
            val portalUrl = o.optString("iptv_url")
            val user = o.optString("iptv_username")
            if (portalUrl.isBlank() || user.isBlank()) return emptyList()
            listOf(
                CloudPortalRow(
                    url = portalUrl,
                    username = user,
                    password = o.optString("iptv_password"),
                    kind = o.optString("iptv_kind", "xtream"),
                    displayName = o.optString("iptv_name"),
                    source = "PlayTorrio Cloud",
                ),
            )
        }.getOrElse {
            Log.w(TAG, "profiles fallback failed: ${it.message}")
            emptyList()
        }
    }

    private fun authorizedGet(url: String, session: CloudSession): String? {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("apikey", CloudConfig.supabaseAnonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .build()
        return SupabaseHttp.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET ${resp.code} $url body=${body.take(200)}")
                null
            } else body
        }
    }
}

data class CloudSyncResult(
    val email: String,
    val imported: Int,
    val totalSaved: Int,
    val failed: List<String> = emptyList(),
)

/** Shared OkHttp client for Supabase REST calls. */
internal object SupabaseHttp {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

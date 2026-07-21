package com.playtorrio.tv.data.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-wide PlayTorrio Cloud account — same Supabase project as PlayTorrioV2.
 * Sign in once in Settings; syncs continue watching, Stremio addons, and IPTV portals.
 */
object PlayTorrioCloudRepository {
    suspend fun signIn(ctx: Context, email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val auth = SupabaseAuthClient.signIn(email, password).getOrElse { return@withContext Result.failure(it) }
            CloudSessionStore.save(ctx, auth)
            pullAll(ctx)
            Result.success(Unit)
        }

    suspend fun pullAll(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx) ?: return@withContext
        PlayTorrioCloudSync.pullWatchHistory(ctx)
        CloudUserSettingsSync.pull(ctx, session)
    }

    suspend fun pushAll(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx)
            ?: throw IllegalStateException("Not signed in to PlayTorrio Cloud.")
        CloudUserSettingsSync.push(ctx, session)
        PlayTorrioCloudSync.pushWatchHistory(ctx)
    }

    suspend fun syncIptvPortals(ctx: Context): Result<CloudSyncResult> =
        CloudIptvRepository.syncWithStoredSession(ctx)

    suspend fun startupPullIfSignedIn(ctx: Context) {
        if (!CloudConfig.isConfigured() || !isSignedIn(ctx)) return
        runCatching { pullAll(ctx) }
    }

    fun signOut(ctx: Context) = SupabaseAuthClient.signOut(ctx)

    fun isSignedIn(ctx: Context): Boolean = SupabaseAuthClient.hasStoredSession(ctx)

    fun signedInEmail(ctx: Context): String? = CloudSessionStore.email(ctx)
}

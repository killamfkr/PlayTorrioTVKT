package com.playtorrio.tv.data.cloud

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide PlayTorrio Cloud account — same Supabase project as PlayTorrioV2.
 * Sign in on the profile screen; each profile syncs continue watching, My List,
 * Stremio addons, and IPTV portals.
 */
object PlayTorrioCloudRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsPushJob: Job? = null
    private var historyPushJob: Job? = null

    suspend fun signIn(ctx: Context, email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val auth = SupabaseAuthClient.signIn(email, password).getOrElse { return@withContext Result.failure(it) }
            CloudSessionStore.save(ctx, auth)
            CloudProfileMetaSync.pullAll(ctx, auth)
            Result.success(Unit)
        }

    /** Pull cloud data for the currently active profile (after profile selection). */
    suspend fun pullForActiveProfile(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx) ?: return@withContext
        pullAll(ctx, session)
    }

    suspend fun pullAll(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx) ?: return@withContext
        pullAll(ctx, session)
    }

    private suspend fun pullAll(ctx: Context, session: CloudSession) {
        PlayTorrioCloudSync.pullWatchHistory(ctx)
        CloudUserSettingsSync.pull(ctx, session)
    }

    suspend fun pushAll(ctx: Context) = withContext(Dispatchers.IO) {
        val session = SupabaseAuthClient.getValidSession(ctx)
            ?: throw IllegalStateException("Not signed in to PlayTorrio Cloud.")
        CloudUserSettingsSync.push(ctx, session)
        PlayTorrioCloudSync.pushWatchHistory(ctx)
        CloudProfileMetaSync.pushActive(ctx, session)
    }

    suspend fun syncIptvPortals(ctx: Context): Result<CloudSyncResult> =
        CloudIptvRepository.syncWithStoredSession(ctx)

    fun schedulePushWatchHistory(ctx: Context) {
        if (!isSignedIn(ctx)) return
        historyPushJob?.cancel()
        historyPushJob = scope.launch {
            delay(3_000)
            runCatching { PlayTorrioCloudSync.pushWatchHistory(ctx) }
        }
    }

    fun schedulePushSettings(ctx: Context) {
        if (!isSignedIn(ctx)) return
        settingsPushJob?.cancel()
        settingsPushJob = scope.launch {
            delay(2_000)
            runCatching {
                val session = SupabaseAuthClient.getValidSession(ctx) ?: return@launch
                CloudUserSettingsSync.push(ctx, session)
            }
        }
    }

    fun signOut(ctx: Context) = SupabaseAuthClient.signOut(ctx)

    fun isSignedIn(ctx: Context): Boolean = SupabaseAuthClient.hasStoredSession(ctx)

    fun signedInEmail(ctx: Context): String? = CloudSessionStore.email(ctx)
}

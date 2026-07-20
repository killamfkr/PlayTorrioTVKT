package com.playtorrio.tv.data.cloud

data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs > 0L && nowMs >= expiresAtMs - 60_000L
}

data class CloudPortalRow(
    val url: String,
    val username: String,
    val password: String,
    val kind: String = "xtream",
    val displayName: String = "",
    val source: String = "PlayTorrio Cloud",
)

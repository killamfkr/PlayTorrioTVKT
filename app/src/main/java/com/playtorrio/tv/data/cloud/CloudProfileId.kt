package com.playtorrio.tv.data.cloud

import com.playtorrio.tv.data.profile.ProfileManager

/**
 * Maps the TV app's string profile ids to PlayTorrioV2 `user_settings.profile_id` (1..4).
 * V2 namespaces IPTV portals per profile slot, not per arbitrary id string.
 */
object CloudProfileId {
    const val MIN_ID = 1
    const val MAX_ID = 4

    fun activeProfileId(): Int {
        val profiles = ProfileManager.loadProfiles()
        val idx = profiles.indexOfFirst { it.id == ProfileManager.activeId() }
        return (if (idx >= 0) idx + 1 else MIN_ID).coerceIn(MIN_ID, MAX_ID)
    }
}

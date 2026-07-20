package com.playtorrio.tv.data.iptv

/** Parsed XMLTV channel metadata. */
data class EpgChannel(
    val id: String,
    val name: String,
    val icon: String = "",
)

/** A single programme entry from XMLTV or Xtream short EPG. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val endMs: Long,
) {
    fun isNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs in startMs until endMs
}

/** Full guide for one portal: channel metadata + programmes keyed by EPG channel id. */
data class EpgGuide(
    val channels: Map<String, EpgChannel>,
    val programsByChannel: Map<String, List<EpgProgram>>,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    fun programsFor(channelId: String): List<EpgProgram> =
        programsByChannel[channelId].orEmpty()

    fun nowProgram(channelId: String, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        programsFor(channelId).firstOrNull { it.isNow(nowMs) }

    fun nextProgram(channelId: String, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        programsFor(channelId).firstOrNull { it.startMs > nowMs }
}

/** Links an [IptvStream] to its EPG channel id for guide display. */
data class GuideChannel(
    val stream: IptvStream,
    val epgId: String,
    val channelName: String,
    val icon: String,
    val now: EpgProgram?,
    val next: EpgProgram?,
)

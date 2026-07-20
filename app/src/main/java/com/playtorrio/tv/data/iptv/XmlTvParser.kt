package com.playtorrio.tv.data.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight XMLTV parser for standard `<channel>` and `<programme>` elements.
 * Handles the common `yyyyMMddHHmmss Z` timestamp format used by most IPTV EPG feeds.
 */
object XmlTvParser {

    fun parse(xml: String): EpgGuide {
        if (xml.isBlank()) return EpgGuide(emptyMap(), emptyMap())

        val channels = mutableMapOf<String, EpgChannel>()
        val programs = mutableMapOf<String, MutableList<EpgProgram>>()

        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var event = parser.eventType
        var inChannel = false
        var channelId = ""
        var channelName = ""
        var channelIcon = ""

        var progChannelId = ""
        var progTitle = ""
        var progDesc = ""
        var progStart = 0L
        var progEnd = 0L
        var textTag = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        channelId = parser.getAttributeValue(null, "id").orEmpty()
                        channelName = channelId
                        channelIcon = ""
                    }
                    "icon" -> if (inChannel) {
                        channelIcon = parser.getAttributeValue(null, "src").orEmpty()
                    }
                    "display-name", "desc", "title" -> textTag = parser.name
                    "programme" -> {
                        progChannelId = parser.getAttributeValue(null, "channel").orEmpty()
                        progStart = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                        progEnd = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                        progTitle = ""
                        progDesc = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (textTag) {
                            "display-name" -> if (inChannel) channelName = text
                            "title" -> progTitle = text
                            "desc" -> progDesc = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        if (channelId.isNotEmpty()) {
                            channels[channelId] = EpgChannel(
                                id = channelId,
                                name = channelName.ifBlank { channelId },
                                icon = channelIcon,
                            )
                        }
                        inChannel = false
                    }
                    "display-name", "desc", "title" -> textTag = ""
                    "programme" -> {
                        if (progChannelId.isNotEmpty() && progTitle.isNotEmpty()) {
                            programs.getOrPut(progChannelId) { mutableListOf() } +=
                                EpgProgram(
                                    channelId = progChannelId,
                                    title = progTitle,
                                    description = progDesc,
                                    startMs = progStart,
                                    endMs = progEnd,
                                )
                        }
                    }
                }
            }
            event = parser.next()
        }

        val sorted = programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return EpgGuide(channels, sorted)
    }

    fun parseXmlTvTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
        val patterns = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmssZ",
            "yyyyMMddHHmmss",
        )
        for (pattern in patterns) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = runCatching { fmt.parse(cleaned)?.time }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return 0L
    }
}

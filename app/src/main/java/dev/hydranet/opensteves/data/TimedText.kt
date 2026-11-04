package dev.hydranet.opensteves.data

import org.json.JSONObject
import java.util.Locale

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
data class Chapter(val startMs: Long, val endMs: Long, val title: String, val imagePath: String, val caption: String)

object TimedText {
    private val timing = Regex("(?m)^\\s*((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})[^\\n]*\\n")

    fun parseSubtitles(text: String): List<SubtitleCue> = blocks(text).mapNotNull { (start, end, body) ->
        // Rick Steves VTT payloads describe photographs, not spoken captions.
        if (body.startsWith("{")) null else {
            val clean = body.replace(Regex("<[^>]*>"), "").trim()
            if (clean.isEmpty()) null else SubtitleCue(start, end, clean)
        }
    }

    fun parseChapters(text: String): List<Chapter> = blocks(text).mapNotNull { (start, end, body) ->
        runCatching {
            val json = JSONObject(body)
            val path = json.optString("src")
            require(!path.startsWith("/") && !path.contains("..") && !path.contains('\\'))
            Chapter(start, end, json.optString("title"), path, json.optString("caption"))
        }.getOrNull()
    }

    fun toVtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
            append(cue.text.replace("\r", "").replace(Regex("\\n\\s*\\n"), "\n")).append("\n\n")
        }
    }

    private fun blocks(source: String): List<Triple<Long, Long, String>> {
        val text = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return text.split(Regex("\\n[ \\t]*\\n")).mapNotNull { block ->
            val match = timing.find("$block\n") ?: return@mapNotNull null
            val start = parseTimestamp(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTimestamp(match.groupValues[2]) ?: return@mapNotNull null
            if (end <= start || end > 7 * 24 * 60 * 60 * 1000L) return@mapNotNull null
            Triple(start, end, block.substring((match.range.last + 1).coerceAtMost(block.length)).trim())
        }.sortedBy { it.first }
    }

    private fun parseTimestamp(value: String): Long? = runCatching {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().split('.')
        val minutes = parts[parts.lastIndex - 1].toLong()
        val second = seconds[0].toLong()
        require(minutes in 0..59 && second in 0..59)
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        (hours * 3600 + minutes * 60 + second) * 1000 + seconds[1].toLong()
    }.getOrNull()

    private fun timestamp(ms: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000,
    )
}

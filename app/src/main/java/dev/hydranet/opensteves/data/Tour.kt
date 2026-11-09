package dev.hydranet.opensteves.data

import org.json.JSONObject

data class Tour(
    val id: Int,
    val title: String,
    val region: String,
    val type: String,
    val description: String,
    val details: String,
    val baseFileName: String,
    val version: Int,
    val bytes: Long,
    val isCustom: Boolean = false,
    val audioExtension: String = "mp3",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    val audioFileName: String get() = "$baseFileName.$audioExtension"
    val audioMimeType: String get() = when (audioExtension) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "audio/$audioExtension"
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("region", region).put("type", type)
        .put("description", description).put("details", details).put("baseFileName", baseFileName)
        .put("version", version).put("bytes", bytes)
        .put("isCustom", isCustom).put("audioExtension", audioExtension)
        .put("artist", artist).put("album", album).put("durationMs", durationMs)

    companion object {
        internal fun fromJson(json: JSONObject, region: String = json.getString("region")): Tour {
            val tour = Tour(
                json.getInt("id"), json.getString("title"), region, json.getString("type"),
                json.optString("description"), json.optString("details"), json.getString("baseFileName"),
                json.getInt("version"), json.getLong("bytes"),
                json.optBoolean("isCustom"), json.optString("audioExtension", "mp3"),
                json.optString("artist"), json.optString("album"), json.optLong("durationMs"),
            )
            require(tour.id != 0 && tour.isCustom == (tour.id < 0) && tour.version > 0 && tour.bytes in 1..MAX_BUNDLE_BYTES) { "Invalid tour metadata" }
            require(tour.baseFileName.matches(Regex("[A-Za-z0-9_.()'-]+")) && !tour.baseFileName.contains("..")) {
                "Invalid tour filename"
            }
            require(tour.audioExtension in AUDIO_EXTENSIONS && (tour.isCustom || tour.audioExtension == "mp3")) { "Unsupported audio format" }
            require(tour.durationMs in 0..MAX_DURATION_MS && (!tour.isCustom || tour.durationMs > 0)) { "Invalid audio duration" }
            require(!tour.isCustom || (tour.title.isNotBlank() && tour.title.length <= 512 && tour.artist.length <= 512 && tour.album.length <= 512)) { "Invalid audio metadata" }
            return tour
        }

        const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
        const val MAX_DURATION_MS = 86_400_000L
        internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg")
    }
}

data class InstalledTour(val tour: Tour, val sizeBytes: Long, val audioHash: String, val directoryName: String = "")
data class DownloadStatus(val progress: Int, val error: String? = null)

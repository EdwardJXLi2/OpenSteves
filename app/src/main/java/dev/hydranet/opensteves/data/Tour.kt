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
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("region", region).put("type", type)
        .put("description", description).put("details", details).put("baseFileName", baseFileName)
        .put("version", version).put("bytes", bytes)

    companion object {
        internal fun fromJson(json: JSONObject, region: String = json.getString("region")): Tour {
            val tour = Tour(
                json.getInt("id"), json.getString("title"), region, json.getString("type"),
                json.optString("description"), json.optString("details"), json.getString("baseFileName"),
                json.getInt("version"), json.getLong("bytes"),
            )
            require(tour.id > 0 && tour.version > 0 && tour.bytes in 1..MAX_BUNDLE_BYTES) { "Invalid tour metadata" }
            require(tour.baseFileName.matches(Regex("[A-Za-z0-9_.()'-]+")) && !tour.baseFileName.contains("..")) {
                "Invalid tour filename"
            }
            return tour
        }

        const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
    }
}

data class InstalledTour(val tour: Tour, val sizeBytes: Long, val audioHash: String, val directoryName: String = "")
data class DownloadStatus(val progress: Int, val error: String? = null)

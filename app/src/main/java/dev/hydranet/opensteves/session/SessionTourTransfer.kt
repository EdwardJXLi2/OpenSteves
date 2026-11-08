package dev.hydranet.opensteves.session

import dev.hydranet.opensteves.data.SharedTourArchive
import dev.hydranet.opensteves.data.Tour
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.UUID

internal class HostDownloadException(message: String) : IOException(message)

internal object SessionTourTransfer {
    val SERVICE_UUID: UUID = UUID.fromString("b9c2086e-d0ee-4b98-84b3-921994681f72")
    private const val VERSION = 1
    private const val MAX_HEADER_BYTES = 131_072
    private const val RECEIVED = 0x4f535452

    fun writeRequest(output: DataOutputStream, audio: PlaybackSnapshot) {
        writeHeader(output, JSONObject().put("id", audio.tourId).put("hash", audio.hash))
    }

    fun readRequest(input: DataInputStream, audio: PlaybackSnapshot) {
        val request = readHeader(input)
        require(request.getInt("id") == audio.tourId && request.getString("hash") == audio.hash) {
            "Requested tour does not match the session"
        }
    }

    fun writeError(output: DataOutputStream, code: String) {
        writeHeader(output, JSONObject().put("error", code))
    }

    fun acknowledge(output: DataOutputStream) {
        output.writeInt(RECEIVED)
        output.flush()
    }

    fun awaitAcknowledgement(input: DataInputStream) {
        require(input.readInt() == RECEIVED) { "Tour receipt was not acknowledged" }
    }

    suspend fun sendArchive(output: DataOutputStream, archive: SharedTourArchive, activity: () -> Unit) {
        val size = archive.file.length()
        require(size in 1..Tour.MAX_BUNDLE_BYTES)
        writeHeader(output, JSONObject().put("tour", archive.tour.toJson())
            .put("hash", archive.audioHash).put("size", size))
        archive.file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var sent = 0L
            while (sent < size) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                if (read < 0) throw EOFException("Tour archive ended early")
                output.write(buffer, 0, read)
                sent += read
                activity()
            }
        }
        output.flush()
    }

    suspend fun receiveArchive(
        input: DataInputStream,
        audio: PlaybackSnapshot,
        destination: File,
        activity: () -> Unit,
        progress: suspend (Int) -> Unit,
    ): Tour {
        val header = readHeader(input)
        if (header.has("error")) throw HostDownloadException(when (header.getString("error")) {
            "busy" -> "The host is sending another tour. Try again shortly."
            else -> "The host could not share this tour. Try again or download locally."
        })
        val tour = Tour.fromJson(header.getJSONObject("tour"))
        require(tour.id == audio.tourId && header.getString("hash") == audio.hash) {
            "The host sent a different tour"
        }
        val size = header.getLong("size")
        require(size in 1..Tour.MAX_BUNDLE_BYTES) { "Invalid tour archive size" }
        activity()
        progress(0)
        destination.outputStream().use { output ->
            val buffer = ByteArray(65_536)
            var received = 0L
            var lastProgress = 0
            while (received < size) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                if (read < 0) throw EOFException("Bluetooth download ended early")
                output.write(buffer, 0, read)
                received += read
                activity()
                val percent = (received * 100 / size).toInt()
                if (percent != lastProgress) {
                    lastProgress = percent
                    progress(percent)
                }
            }
        }
        return tour
    }

    private fun writeHeader(output: DataOutputStream, header: JSONObject) {
        val bytes = header.put("version", VERSION).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_HEADER_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readHeader(input: DataInputStream): JSONObject {
        val size = input.readInt()
        require(size in 1..MAX_HEADER_BYTES) { "Invalid transfer header size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8)).also {
            require(it.getInt("version") == VERSION) { "Unsupported transfer version" }
        }
    }
}

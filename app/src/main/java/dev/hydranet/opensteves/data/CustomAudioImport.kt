package dev.hydranet.opensteves.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile

internal data class CustomAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val extension: String,
    val artwork: ByteArray?,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal object CustomAudioImport {
    const val MAX_AUDIO_BYTES = Tour.MAX_BUNDLE_BYTES - 4L * 1024 * 1024

    suspend fun read(file: File, displayName: String): CustomAudioMetadata {
        val seekMap = seekMap(file)
        require(seekMap.isSeekable) { "This audio does not support seeking. Choose a seekable MP3, M4A, WAV, FLAC, or Ogg file." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("This file has no audio track")
            require(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null) {
                "This audio format is not supported on this phone."
            }
        } finally {
            extractor.release()
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun text(key: Int): String = retriever.extractMetadata(key)?.trim()?.take(512).orEmpty()
            val duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()
                ?: (seekMap.durationUs / 1_000).takeIf { it > 0 }
                ?: throw IOException("Could not read this audio's duration")
            require(duration in 1..Tour.MAX_DURATION_MS) { "Choose an audio file shorter than 24 hours." }
            val mime = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val extension = when (mime) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "video/mp4", "audio/x-m4a" -> "m4a"
                "audio/aac", "audio/aac-adts" -> "aac"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
                "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                else -> throw IOException("Choose an MP3, M4A, AAC, WAV, FLAC, or Ogg audio file.")
            }
            return CustomAudioMetadata(
                text(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank {
                    displayName.substringBeforeLast('.', displayName).trim().take(512).ifBlank { "Imported audio" }
                },
                text(MediaMetadataRetriever.METADATA_KEY_ARTIST), text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                duration, extension, retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 },
            )
        } finally {
            retriever.release()
        }
    }

    fun writeArtwork(bytes: ByteArray?, destination: File) {
        if (bytes == null) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        try {
            destination.parentFile?.mkdirs()
            val saved = destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!saved || destination.length() > 2 * 1024 * 1024) destination.delete()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun seekMap(file: File): SeekMap {
        val coroutineContext = currentCoroutineContext()
        RandomAccessFile(file, "r").use { source ->
            var readBytes = 0L
            val reader = DataReader { target, offset, length ->
                coroutineContext.ensureActive()
                source.read(target, offset, length).also {
                    if (it > 0) readBytes += it
                    require(readBytes <= 32L * 1024 * 1024) { "The audio header is too large" }
                }
            }
            var input = DefaultExtractorInput(reader, 0, file.length())
            val extractors = DefaultExtractorsFactory().createExtractors()
            val extractor = extractors.firstOrNull {
                coroutineContext.ensureActive()
                input.resetPeekPosition()
                try { it.sniff(input) } catch (_: EOFException) { false }
            } ?: throw IOException("This is not a supported audio file")
            input.resetPeekPosition()
            var map: SeekMap? = null
            var audio = false
            var video = false
            var tracksEnded = false
            try {
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type == C.TRACK_TYPE_AUDIO) audio = true
                        if (type == C.TRACK_TYPE_VIDEO) video = true
                        return DiscardingTrackOutput()
                    }
                    override fun endTracks() { tracksEnded = true }
                    override fun seekMap(seekMap: SeekMap) { map = seekMap }
                })
                val seek = PositionHolder()
                var iterations = 0
                while (map == null || !tracksEnded) {
                    currentCoroutineContext().ensureActive()
                    require(++iterations <= 100_000) { "Could not read the audio header" }
                    when (extractor.read(input, seek)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            require(seek.position in 0..file.length())
                            source.seek(seek.position)
                            input = DefaultExtractorInput(reader, seek.position, file.length())
                        }
                    }
                }
                require(audio && !video) { "Choose an audio file without video tracks." }
                return map ?: throw IOException("Could not read this audio's seek information")
            } finally {
                extractors.forEach(Extractor::release)
            }
        }
    }
}

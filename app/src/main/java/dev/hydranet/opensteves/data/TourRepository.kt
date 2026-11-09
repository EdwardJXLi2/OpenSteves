package dev.hydranet.opensteves.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.hydranet.opensteves.download.DownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TourRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.filesDir, "tours")
    private val catalogFile = File(context.filesDir, "catalog.json")
    private val catalogMutex = Mutex()
    private val customImportMutex = Mutex()
    private val tourLocks = ConcurrentHashMap<Int, Mutex>()
    private val _tours = MutableStateFlow<List<Tour>>(emptyList())
    val tours: StateFlow<List<Tour>> = _tours.asStateFlow()
    private val _installed = MutableStateFlow<Map<Int, InstalledTour>>(emptyMap())
    val installed: StateFlow<Map<Int, InstalledTour>> = _installed.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _downloads = MutableStateFlow<Map<Int, DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<Int, DownloadStatus>> = _downloads.asStateFlow()
    private val initialized = scope.async {
        root.mkdirs()
        if (catalogFile.exists()) runCatching { parseCatalog(AtomicFile(catalogFile).readFully().decodeToString()) }
            .onSuccess { _tours.value = it }
        reloadInstalled()
        _installed.value.forEach { (id, item) ->
            File(root, id.toString()).listFiles().orEmpty()
                .filter { it.isDirectory && it.name.matches(Regex("bundle-[a-f0-9-]+")) && it.name != item.directoryName }
                .forEach(File::deleteRecursively)
        }
    }

    init {
        scope.launch {
            WorkManager.getInstance(context).getWorkInfosByTagFlow(DOWNLOAD_TAG).collect { jobs ->
                _downloads.value = jobs.groupBy { it.tags.firstOrNull { tag -> tag.startsWith("tour:") } }
                    .mapNotNull { (tag, work) ->
                        val id = tag?.substringAfter(':')?.toIntOrNull() ?: return@mapNotNull null
                        val active = work.firstOrNull { !it.state.isFinished }
                        if (active != null) id to DownloadStatus(active.progress.getInt("progress", 0))
                        else {
                            val failed = work.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith("requested:") }?.substringAfter(':')?.toLongOrNull() ?: 0L }
                            if (failed?.state == WorkInfo.State.FAILED)
                                id to DownloadStatus(0, failed.outputData.getString("error") ?: "Download failed. Try again.")
                            else null
                        }
                    }.toMap()
            }
        }
    }

    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        initialized.await()
        catalogMutex.withLock {
            var connection: HttpURLConnection? = null
            var cancellationCloser: Job? = null
            try {
                connection = connection(CATALOG_URL)
                cancellationCloser = disconnectOnCancellation(connection)
                val preferences = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
                if (catalogFile.exists()) preferences.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                    HttpURLConnection.HTTP_OK -> {
                        val text = connection.inputStream.use { readLimited(it, 4 * 1024 * 1024).decodeToString() }
                        val parsed = parseCatalog(text)
                        atomicWrite(catalogFile, text)
                        preferences.edit().putString("etag", connection.getHeaderField("ETag")).apply()
                        _tours.value = parsed
                    }
                    else -> throw IOException("Catalog server returned HTTP $status")
                }
                _message.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = if (_tours.value.isEmpty()) "Could not load tours. Check your connection and refresh." else "Offline catalog shown. Refresh when connected."
            } finally { cancellationCloser?.cancel(); connection?.disconnect() }
        }
    }

    fun download(tour: Tour) {
        require(!tour.isCustom) { "Custom audio is shared through Bluetooth or imported from a file." }
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("tour" to tour.toJson().toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(DOWNLOAD_TAG).addTag("tour:${tour.id}").addTag("requested:${System.currentTimeMillis()}").build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(tour.id), ExistingWorkPolicy.KEEP, work)
    }

    fun cancelDownload(id: Int) {
        scope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
            lock(id).withLock { deletePartialFiles(id) }
        }
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        initialized.await()
        WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
        lock(id).withLock {
            val directory = File(root, id.toString())
            if (directory.exists() && !directory.deleteRecursively()) throw IOException("Could not delete the downloaded tour")
            deletePartialFiles(id)
            _installed.update { it - id }
        }
    }

    fun audioFile(id: Int): File? = _installed.value[id]?.let {
        File(directory(id), it.tour.audioFileName).takeIf(File::isFile)
    }

    fun localAudioId(tourId: Int, hash: String): Int? = if (tourId < 0) {
        _installed.value.values.firstOrNull {
            it.tour.isCustom && it.audioHash == hash && audioFile(it.tour.id) != null
        }?.tour?.id
    } else tourId.takeIf { _installed.value[it]?.audioHash == hash && audioFile(it) != null }
    fun mapFile(id: Int): File? = asset(id, "png")
    fun transcriptFile(id: Int): File? = asset(id, "txt")
    fun chapterFile(id: Int): File? = asset(id, "vtt")
    fun subtitlesFile(id: Int): File? = directory(id)?.let { File(it, "subtitles.vtt").takeIf(File::isFile) } ?: chapterFile(id)
    fun imageFile(id: Int, path: String = "images/01.jpg"): File? {
        val folder = directory(id) ?: return null
        if (path.contains("..") || path.startsWith('/') || path.contains('\\')) return null
        return File(folder, path).takeIf { it.isFile && it.canonicalPath.startsWith(folder.canonicalPath + File.separator) }
    }

    suspend fun reloadInstalled() = withContext(Dispatchers.IO) {
        _installed.value = root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { folder ->
            runCatching {
                val json = JSONObject(AtomicFile(File(folder, "install.json")).readFully().decodeToString())
                val tour = Tour.fromJson(json.getJSONObject("tour"))
                val directoryName = json.getString("directory")
                require(directoryName.matches(Regex("bundle-[a-f0-9-]+")))
                require(folder.name == tour.id.toString())
                val bundle = File(folder, directoryName)
                require(File(bundle, tour.audioFileName).isFile)
                tour.id to InstalledTour(tour, json.getLong("sizeBytes"), json.getString("audioHash"), directoryName)
            }.getOrNull()
        }.toMap()
    }

    suspend fun importAudio(uri: Uri): Tour = withContext(Dispatchers.IO) {
        initialized.await()
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Imported audio"
        val imported = File.createTempFile("imported-audio-", ".audio", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                imported.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= CustomAudioImport.MAX_AUDIO_BYTES) { "Choose an audio file smaller than 508 MiB." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    require(size > 0) { "The selected audio file is empty." }
                }
            } ?: throw IOException("Could not open this audio file")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val metadata = CustomAudioImport.read(imported, displayName)
            customImportMutex.withLock {
                localAudioId(-1, hash)?.let { return@withLock _installed.value.getValue(it).tour }
                val tour = Tour(
                    customAudioId(hash), metadata.title, "", "Custom audio", "", "", "audio", 1, imported.length(),
                    isCustom = true, audioExtension = metadata.extension, artist = metadata.artist,
                    album = metadata.album, durationMs = metadata.durationMs,
                )
                lock(tour.id).withLock {
                    val stage = newStage(tour.id)
                    var published = false
                    try {
                        require(imported.renameTo(File(stage, tour.audioFileName))) { "Could not save the imported audio" }
                        CustomAudioImport.writeArtwork(metadata.artwork, File(stage, "images/01.jpg"))
                        publishInstall(tour, stage, hash)
                        published = true
                        tour
                    } finally {
                        if (!published) stage.deleteRecursively()
                    }
                }
            }
        } finally {
            imported.delete()
        }
    }

    suspend fun importSubtitles(id: Int, uri: Uri) = withContext(Dispatchers.IO) {
        initialized.await()
        val text = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 5 * 1024 * 1024).decodeToString() }
            ?: throw IOException("Could not open subtitle file")
        val cues = TimedText.parseSubtitles(text)
        require(cues.isNotEmpty()) { "No timed subtitles found. Choose an SRT or caption WebVTT file." }
        saveSubtitles(id, cues)
    }

    // The destination must be a new document created for this export.
    suspend fun exportAudio(id: Int, destination: Uri) = withContext(Dispatchers.IO) {
        try {
            initialized.await()
            lock(id).withLock {
                val audio = audioFile(id) ?: throw IOException("Download this audio first.")
                audio.inputStream().use { input ->
                    val output = context.contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Could not open the destination file.")
                    output.use {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            it.write(buffer, 0, count)
                        }
                        currentCoroutineContext().ensureActive()
                        it.flush()
                    }
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            }
            throw error
        }
    }

    suspend fun generateSubtitles(id: Int, endpoint: String, apiKey: String, model: String) = withContext(Dispatchers.IO) {
        initialized.await()
        require(model.isNotBlank() && !model.contains('\r') && !model.contains('\n')) { "Enter a transcription model" }
        val url = URL(endpoint.trim())
        require(url.protocol == "https" && url.userInfo == null) { "Use an HTTPS transcription endpoint" }
        require(!apiKey.contains('\r') && !apiKey.contains('\n')) { "Invalid API key" }
        val audio = audioFile(id) ?: throw IOException("Download this tour first")
        val tour = _installed.value.getValue(id).tour
        val audioExtension = tour.audioExtension
        val expectedHash = _installed.value[id]?.audioHash ?: throw IOException("Download this tour first")
        val boundary = "OpenSteves${UUID.randomUUID()}"
        val connection = connection(url.toString()).apply {
            requestMethod = "POST"
            doOutput = true
            readTimeout = 180_000
            instanceFollowRedirects = false
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val cancellationCloser = disconnectOnCancellation(connection)
        try {
            connection.outputStream.buffered().use { output ->
                fun field(name: String, value: String) {
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", model)
                field("response_format", "verbose_json")
                field("timestamp_granularities[]", "segment")
                val mime = tour.audioMimeType
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.$audioExtension\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
                audio.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException(
                when (status) {
                    401, 403 -> "The transcription service rejected the API key."
                    413 -> "This audio exceeds the service's upload limit. Import subtitles or use a service with a larger limit."
                    429 -> "The transcription service quota or rate limit was reached."
                    else -> "Transcription failed (HTTP $status). Check the endpoint and model."
                },
            )
            val json = JSONObject(connection.inputStream.use { readLimited(it, 5 * 1024 * 1024).decodeToString() })
            val segments = json.optJSONArray("segments") ?: throw IOException("The service did not return segment timestamps. Choose a model that supports verbose_json segments.")
            val cues = (0 until segments.length()).mapNotNull { index ->
                val segment = segments.getJSONObject(index)
                val start = segment.getDouble("start")
                val end = segment.getDouble("end")
                val text = segment.getString("text").trim()
                if (start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 604800 && text.isNotEmpty())
                    SubtitleCue((start * 1000).toLong(), (end * 1000).toLong(), text) else null
            }.sortedBy { it.startMs }
            require(cues.isNotEmpty()) { "The service returned no timed subtitles" }
            saveSubtitles(id, cues, expectedHash)
        } finally { cancellationCloser.cancel(); connection.disconnect() }
    }

    internal suspend fun installDownload(tour: Tour, onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(!tour.isCustom) { "Custom audio is shared through Bluetooth or imported from a file." }
        initialized.await()
        lock(tour.id).withLock {
            val partial = File(root, "${tour.id}-${tour.version}.partial")
            try {
                downloadArchive(tour, partial, onProgress)
                installArchive(tour, partial)
                deletePartialFiles(tour.id)
                onProgress(100)
            } catch (error: java.util.zip.ZipException) {
                partial.delete()
                throw IOException("The downloaded archive is damaged. Try downloading again.", error)
            } catch (error: IllegalArgumentException) {
                partial.delete()
                throw error
            }
        }
    }

    internal suspend fun exportForSharing(tourId: Int, expectedHash: String): SharedTourArchive {
        var archive: File? = null
        try {
            return withContext(Dispatchers.IO) {
                initialized.await()
                lock(tourId).withLock {
                    val installed = _installed.value[tourId] ?: throw IOException("This tour is no longer downloaded")
                    require(installed.audioHash == expectedHash) { "The host's audio changed. Rejoin the session." }
                    val folder = directory(tourId) ?: throw IOException("The downloaded tour is missing")
                    val audio = File(folder, installed.tour.audioFileName)
                    require(audio.isFile && audio.length() > 0 && audioHash(audio) == expectedHash) {
                        "The host's audio changed. Download the tour again."
                    }
                    val file = File.createTempFile("shared-tour-$tourId-", ".zip", context.cacheDir)
                    archive = file
                    SharedTourArchiveWriter.write(installed.tour, folder, file)
                    SharedTourArchive(installed.tour, installed.audioHash, file)
                }
            }
        } catch (error: Throwable) {
            archive?.delete()
            throw error
        }
    }

    internal suspend fun installSharedTour(tour: Tour, archive: File, expectedHash: String): Tour = withContext(Dispatchers.IO) {
        initialized.await()
        val validatedTour = Tour.fromJson(tour.toJson())
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid shared audio hash" }
        require(archive.isFile && archive.length() in 1..Tour.MAX_BUNDLE_BYTES) { "Invalid shared tour archive" }
        if (validatedTour.isCustom) customImportMutex.withLock {
            localAudioId(validatedTour.id, expectedHash)?.let { return@withLock _installed.value.getValue(it).tour }
            val local = validatedTour.copy(id = customAudioId(expectedHash, validatedTour.id))
            lock(local.id).withLock {
                installArchive(local, archive, expectedHash)
                local
            }
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(workName(validatedTour.id)).result.get()
            lock(validatedTour.id).withLock {
                installArchive(validatedTour, archive, expectedHash)
                deletePartialFiles(validatedTour.id)
                validatedTour
            }
        }
    }

    private fun customAudioId(hash: String, preferred: Int? = null): Int {
        var id = preferred ?: -(hash.take(8).toLong(16).rem(Int.MAX_VALUE) + 1).toInt()
        while (_installed.value.containsKey(id)) id = if (id == Int.MIN_VALUE) -1 else id - 1
        return id
    }

    private fun newStage(id: Int): File = File(root, "$id/bundle-${UUID.randomUUID()}").apply {
        require(mkdirs()) { "Could not create audio storage" }
    }

    private suspend fun installArchive(tour: Tour, archive: File, expectedHash: String? = null) {
        val stage = newStage(tour.id)
        var published = false
        try {
            BundleArchive.extract(tour, archive, stage)
            val audio = File(stage, tour.audioFileName)
            require(audio.isFile && audio.length() > 0) { "The bundle has no audio file" }
            val hash = audioHash(audio)
            require(expectedHash == null || hash == expectedHash) { "The shared audio does not match the host's audio" }
            publishInstall(tour, stage, hash)
            published = true
        } finally {
            if (!published) stage.deleteRecursively()
        }
    }

    private suspend fun publishInstall(tour: Tour, stage: File, hash: String) {
        val old = directory(tour.id)
        if (_installed.value[tour.id]?.audioHash == hash) {
            old?.let { File(it, "subtitles.vtt") }?.takeIf(File::isFile)
                ?.copyTo(File(stage, "subtitles.vtt"), overwrite = true)
        }
        val size = stage.walkTopDown().filter(File::isFile).sumOf(File::length)
        val metadata = JSONObject().put("tour", tour.toJson()).put("directory", stage.name)
            .put("sizeBytes", size).put("audioHash", hash)
        currentCoroutineContext().ensureActive()
        atomicWrite(File(stage.parentFile, "install.json"), metadata.toString())
        _installed.update { it + (tour.id to InstalledTour(tour, size, hash, stage.name)) }
        runCatching { old?.deleteRecursively() }
    }

    private suspend fun audioHash(audio: File): String = audio.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadArchive(tour: Tour, partial: File, onProgress: suspend (Int) -> Unit) {
        if (partial.length() > tour.bytes) partial.delete()
        val offset = partial.length()
        if (offset == tour.bytes) return
        val connection = connection("$BUNDLE_URL/${tour.baseFileName}.zip")
        val cancellationCloser = disconnectOnCancellation(connection)
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            if (status != 200 && status != 206) throw IOException("Download server returned HTTP $status")
            val append = offset > 0 && status == 206
            if (status == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                require(range != null && range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == tour.bytes) { "Invalid resumed download" }
            }
            var written = if (append) offset else 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= tour.bytes) { "Downloaded bundle exceeds its expected size" }
                        output.write(buffer, 0, count)
                        val progress = (written * 95 / tour.bytes).toInt()
                        if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                    }
                    output.fd.sync()
                }
            }
            if (written != tour.bytes) throw IOException("Download was interrupted. Try again to resume.")
        } finally { cancellationCloser.cancel(); connection.disconnect() }
    }

    private suspend fun saveSubtitles(id: Int, cues: List<SubtitleCue>, expectedHash: String? = null) {
        lock(id).withLock {
            val folder = directory(id) ?: throw IOException("Download this tour first")
            require(expectedHash == null || _installed.value[id]?.audioHash == expectedHash) { "The audio changed during transcription. Generate subtitles again." }
            atomicWrite(File(folder, "subtitles.vtt"), TimedText.toVtt(cues))
            val old = _installed.value[id] ?: return
            val size = folder.walkTopDown().filter(File::isFile).sumOf(File::length)
            val metadata = JSONObject().put("tour", old.tour.toJson()).put("directory", old.directoryName)
                .put("sizeBytes", size).put("audioHash", old.audioHash)
            atomicWrite(File(root, "$id/install.json"), metadata.toString())
            _installed.update { it + (id to old.copy(sizeBytes = size)) }
        }
    }

    private fun deletePartialFiles(id: Int) {
        val pattern = Regex("$id-[0-9]+\\.partial")
        root.listFiles().orEmpty().filter { it.isFile && pattern.matches(it.name) }.forEach(File::delete)
    }

    private fun directory(id: Int): File? = _installed.value[id]?.let { File(root, "$id/${it.directoryName}") }
    private fun asset(id: Int, extension: String): File? = _installed.value[id]?.let {
        File(directory(id), "${it.tour.baseFileName}.$extension").takeIf(File::isFile)
    }
    private fun lock(id: Int): Mutex = tourLocks.getOrPut(id) { Mutex() }

    private suspend fun disconnectOnCancellation(connection: HttpURLConnection): Job =
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }

    companion object {
        const val DOWNLOAD_TAG = "tour-download"
        private const val CATALOG_URL = "https://s3-us-west-2.amazonaws.com/rs-ae-app/tracks.json"
        private const val BUNDLE_URL = "https://d380dv1mg5nf0o.cloudfront.net"
        @Volatile private var instance: TourRepository? = null
        fun get(context: Context): TourRepository = instance ?: synchronized(this) {
            instance ?: TourRepository(context.applicationContext).also { instance = it }
        }
        fun workName(id: Int): String = "tour-download-$id"

        internal fun parseCatalog(text: String): List<Tour> {
            val root = JSONObject(text)
            val result = buildList {
                root.keys().forEach { region ->
                    val tracks = root.getJSONObject(region)
                    tracks.keys().forEach { title ->
                        val tour = Tour.fromJson(tracks.getJSONObject(title), region)
                        require(!tour.isCustom) { "Invalid catalog audio source" }
                        add(tour)
                    }
                }
            }
            require(result.isNotEmpty() && result.size <= 20_000 && result.distinctBy { it.id }.size == result.size) { "Invalid tour catalog" }
            return result
        }

        private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
        }

        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= limit) { "The response is too large" }
                bytes.write(buffer, 0, count)
            }
            return bytes.toByteArray()
        }

        private fun atomicWrite(file: File, text: String) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
    }
}

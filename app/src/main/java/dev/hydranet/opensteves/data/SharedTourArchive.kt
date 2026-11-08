package dev.hydranet.opensteves.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SharedTourArchive(val tour: Tour, val audioHash: String, val file: File)

internal object SharedTourArchiveWriter {
    suspend fun write(tour: Tour, folder: File, archive: File) {
        val rootPath = folder.canonicalPath + File.separator
        var entries = 0
        var extractedBytes = 0L
        archive.outputStream().use { fileOutput ->
            ZipOutputStream(object : OutputStream() {
                private var written = 0L

                override fun write(value: Int) {
                    require(written < Tour.MAX_BUNDLE_BYTES) { "The shared tour exceeds the transfer limit" }
                    fileOutput.write(value)
                    written++
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(length <= Tour.MAX_BUNDLE_BYTES - written) { "The shared tour exceeds the transfer limit" }
                    fileOutput.write(bytes, offset, length)
                    written += length
                }
            }).use { zip ->
                val buffer = ByteArray(64 * 1024)
                for (file in folder.walkTopDown()) {
                    currentCoroutineContext().ensureActive()
                    if (file == folder) continue
                    require(file.canonicalPath.startsWith(rootPath)) { "Unsafe path in the installed tour" }
                    require(++entries <= 5000) { "The tour contains too many files" }
                    val relative = file.relativeTo(folder).invariantSeparatorsPath
                    require(!relative.contains('\\')) { "Unsafe path in the installed tour" }
                    val name = "${tour.baseFileName}/$relative" + if (file.isDirectory) "/" else ""
                    zip.putNextEntry(ZipEntry(name))
                    if (file.isFile) {
                        file.inputStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= 768L * 1024 * 1024) { "The tour exceeds the storage limit" }
                                zip.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}

package dev.hydranet.opensteves.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal object BundleArchive {
    suspend fun extract(tour: Tour, archive: File, stage: File) {
        val prefix = "${tour.baseFileName}/"
        var total = 0L
        var files = 0
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = entries.nextElement()
                require(++files <= 5000) { "The bundle contains too many files" }
                if (entry.name.startsWith("__MACOSX/") || entry.name.substringAfterLast('/').startsWith("._") || entry.name.endsWith(".DS_Store")) continue
                require(entry.name.startsWith(prefix)) { "Unexpected folder in tour bundle" }
                val relative = entry.name.removePrefix(prefix)
                if (relative.isEmpty()) continue
                val target = File(stage, relative)
                require(!relative.contains('\\') && target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Unsafe path in tour bundle" }
                if (entry.isDirectory) { target.mkdirs(); continue }
                require(!target.exists()) { "Duplicate file in tour bundle" }
                target.parentFile?.mkdirs()
                val checksum = CRC32()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_EXTRACTED_BYTES) { "The extracted bundle exceeds the storage limit" }
                            checksum.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(entry.crc == checksum.value) { "A file in the downloaded archive is damaged" }
            }
        }
    }

    private const val MAX_EXTRACTED_BYTES = 768L * 1024 * 1024
}

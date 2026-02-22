package com.example.structpulse.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipSharer(private val context: Context) {

    fun zipAndShareSession(sessionDir: File, sessionId: String) {
        require(sessionDir.exists() && sessionDir.isDirectory) {
            "Invalid sessionDir: ${sessionDir.absolutePath}"
        }

        // Zip location: .../files/sessions/<sessionId>.zip
        val zipFile = File(sessionDir.parentFile, "$sessionId.zip")
        createZip(sourceDir = sessionDir, zipFile = zipFile)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)

            // ✅ critical for Drive
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // ✅ improves compatibility (Drive sometimes needs this)
            clipData = ClipData.newUri(context.contentResolver, "session_zip", uri)
        }

        val chooser = Intent.createChooser(share, "Export session")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun createZip(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))

                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zos, bufferSize = 8 * 1024)
                    }

                    zos.closeEntry()
                }
        }
    }
}

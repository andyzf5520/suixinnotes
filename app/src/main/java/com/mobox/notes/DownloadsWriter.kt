package com.mobox.notes

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object DownloadsWriter {
    fun write(context: Context, name: String, data: ByteArray): String {
        require(name.isNotBlank() && name.length <= 200 && !name.contains('/') && !name.contains('\\'))
        val mime = when(name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"; "txt" -> "text/plain"; "csv" -> "text/csv"; else -> "application/octet-stream"
        }
        if(Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Notes/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(data); it.flush() } ?: error("无法写入下载目录")
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "文件发布失败" }
                val actualName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if(it.moveToFirst()) it.getString(0) else name
                } ?: name
                return "Download/Notes/$actualName"
            } catch(e: Exception) {
                try { resolver.delete(uri, null, null) } catch(cleanup: Exception) { e.addSuppressed(cleanup) }
                throw e
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Notes")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 Download/Notes" }
        var file = File(directory, name)
        var suffix = 0
        while(!file.createNewFile()) {
            suffix++; require(suffix <= 1000) { "同名文件过多" }
            file = File(directory, name.substringBeforeLast('.') + "-$suffix." + name.substringAfterLast('.'))
        }
        try { FileOutputStream(file).use { it.write(data); it.fd.sync() } }
        catch(e: Exception) { file.delete(); throw e }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return "Download/Notes/${file.name}"
    }
}

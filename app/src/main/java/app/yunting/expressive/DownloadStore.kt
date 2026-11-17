package app.yunting.expressive

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal enum class DownloadStatus {
    Queued, Waiting, Transferring, Complete, Failed
}

internal data class DownloadEntry(
    val song: Song,
    val status: DownloadStatus,
    val progress: Float? = null,
    val error: String? = null,
)

internal class DownloadStore(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val records = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "downloaded-audio")

    suspend fun enqueue(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.id.toLongOrNull()?.let { it > 0 } != true || song.localUri != null) {
            return@withContext "这首歌无需下载"
        }
        val manager = manager ?: return@withContext "系统下载服务不可用"
        synchronized(lock) { alreadyQueued(song.id) }?.let { return@withContext it }
        val source = runCatching { Catalog.playbackUri(song.id, requireFull = true) }.getOrNull()
            ?: return@withContext "暂无可下载的完整音源，请检查账号权限与网络"

        synchronized(lock) {
            alreadyQueued(song.id)?.let { return@synchronized it }
            try {
                val old = read(song.id)
                if (old != null && old.downloadId > 0) manager.remove(old.downloadId)
                val request = DownloadManager.Request(source)
                    .setTitle(song.title)
                    .setDescription(song.artist)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "${UUID.randomUUID()}.audio")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                val downloadId = manager.enqueue(request)
                if (!save(Record(song, downloadId, System.currentTimeMillis()))) {
                    manager.remove(downloadId)
                    "无法保存下载记录"
                } else {
                    null
                }
            } catch (_: Exception) {
                "无法加入下载队列"
            }
        }
    }

    suspend fun entries(): List<DownloadEntry> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            records.all.keys.mapNotNull(::read).sortedByDescending(Record::startedAt).map(::entry)
        }
    }

    suspend fun remove(songId: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val record = read(songId) ?: return@synchronized null
            try {
                val file = audioFile(songId)
                if (file.exists() && !file.delete()) return@synchronized "无法删除离线文件"
                if (record.downloadId > 0) manager?.remove(record.downloadId)
                if (records.edit().remove(songId).commit()) null else "无法移除下载记录"
            } catch (_: Exception) {
                "无法移除下载"
            }
        }
    }

    // The resolver runs on a Media3 loader thread. Keep the original song ID in the queue.
    fun offlineUri(songId: String): Uri? = synchronized(lock) {
        val record = read(songId) ?: return@synchronized null
        if (entry(record).status == DownloadStatus.Complete) Uri.fromFile(audioFile(songId)) else null
    }

    fun complete(downloadId: Long) {
        if (downloadId <= 0) return
        synchronized(lock) {
            records.all.keys.asSequence().mapNotNull(::read)
                .firstOrNull { it.downloadId == downloadId }?.let(::entry)
        }
    }

    private fun alreadyQueued(songId: String): String? {
        val record = read(songId) ?: return null
        return when (entry(record).status) {
            DownloadStatus.Complete -> "已保存离线文件"
            DownloadStatus.Failed -> null
            else -> "已在下载队列"
        }
    }

    private fun entry(record: Record): DownloadEntry {
        val song = record.song
        if (audioFile(song.id).length() > 0) {
            if (record.downloadId > 0 && save(record.copy(downloadId = 0))) {
                runCatching { manager?.remove(record.downloadId) }
            }
            return DownloadEntry(song, DownloadStatus.Complete)
        }
        record.error?.let { return DownloadEntry(song, DownloadStatus.Failed, error = it) }
        val transfer = transfer(record.downloadId)
            ?: return DownloadEntry(song, DownloadStatus.Failed, error = "文件或下载记录已不可用")
        return when (transfer.status) {
            DownloadManager.STATUS_SUCCESSFUL -> finish(record)
            DownloadManager.STATUS_PENDING -> DownloadEntry(song, DownloadStatus.Queued)
            DownloadManager.STATUS_RUNNING -> DownloadEntry(song, DownloadStatus.Transferring, transfer.progress)
            DownloadManager.STATUS_PAUSED -> DownloadEntry(song, DownloadStatus.Waiting, transfer.progress)
            else -> DownloadEntry(song, DownloadStatus.Failed, error = "下载失败，可重新下载")
        }
    }

    private fun transfer(id: Long): Transfer? = runCatching {
        if (id <= 0) return null
        manager?.query(DownloadManager.Query().setFilterById(id))?.use { row ->
            if (!row.moveToFirst()) return@use null
            val status = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            Transfer(status, if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null)
        }
    }.getOrNull()

    private fun finish(record: Record): DownloadEntry {
        val manager = manager ?: return DownloadEntry(record.song, DownloadStatus.Failed, error = "系统下载服务不可用")
        val destination = audioFile(record.song.id)
        val temp = File(directory, "${record.song.id}.partial")
        try {
            val mime = manager.getMimeTypeForDownloadedFile(record.downloadId)?.substringBefore(';')?.lowercase()
            if (mime != null && !mime.startsWith("audio/") &&
                mime !in setOf("application/octet-stream", "application/mp4", "video/mp4")) {
                val error = "下载的文件不是音频"
                if (save(record.copy(downloadId = 0, error = error))) manager.remove(record.downloadId)
                return DownloadEntry(record.song, DownloadStatus.Failed, error = error)
            }
            directory.mkdirs()
            ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(record.downloadId)).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0 || !temp.renameTo(destination)) {
                return DownloadEntry(record.song, DownloadStatus.Failed, error = "无法保存离线文件")
            }
            // Keep the finished copy in internal storage after removing the temporary system download.
            if (save(record.copy(downloadId = 0))) runCatching { manager.remove(record.downloadId) }
            return DownloadEntry(record.song, DownloadStatus.Complete)
        } catch (_: Exception) {
            return DownloadEntry(record.song, DownloadStatus.Failed, error = "无法保存离线文件")
        } finally {
            temp.delete()
        }
    }

    private fun audioFile(songId: String) = File(directory, "$songId.audio")

    private fun read(songId: String): Record? {
        if (songId.toLongOrNull()?.let { it > 0 } != true) return null
        val value = records.getString(songId, null) ?: return null
        return runCatching {
            val row = JSONObject(value)
            val song = Song(songId, row.getString("title"), row.getString("artist"),
                row.optString("album"), row.optString("cover"),
                artistId = row.optLong("artistId"), albumId = row.optLong("albumId"))
            Record(song, row.getLong("downloadId"), row.getLong("startedAt"),
                row.optString("error").takeIf(String::isNotBlank))
        }.getOrNull()
    }

    private fun save(record: Record): Boolean {
        val song = record.song
        val row = JSONObject().put("title", song.title).put("artist", song.artist)
            .put("album", song.album).put("cover", song.cover)
            .put("artistId", song.artistId).put("albumId", song.albumId)
            .put("downloadId", record.downloadId).put("startedAt", record.startedAt).put("error", record.error)
        return records.edit().putString(song.id, row.toString()).commit()
    }

    private data class Record(val song: Song, val downloadId: Long, val startedAt: Long, val error: String? = null)
    private data class Transfer(val status: Int, val progress: Float?)

    private companion object {
        val lock = Any()
    }
}

class DownloadCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        executor.execute {
            try {
                DownloadStore(context).complete(id)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}

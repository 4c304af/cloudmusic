package app.yunting.expressive

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal data class CloudUploadStatus(
    val running: Boolean = false,
    val file: String = "",
    val stage: String = "",
    val progress: Int? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val stopped: Boolean = false,
    val errors: List<String> = emptyList(),
    val revision: Int = 0,
)

internal class CloudUploadModel : ViewModel() {
    private val mutableStatus = MutableStateFlow(CloudUploadStatus())
    val status = mutableStatus.asStateFlow()
    private var failed = emptyList<Uri>()
    private var uploadJob: Job? = null

    fun upload(context: Context, accountId: Long, files: List<Uri>) {
        val documents = files.distinct().filter { it.scheme == "content" }
        if (documents.isEmpty() || mutableStatus.value.running) return
        val application = context.applicationContext
        failed = emptyList()
        mutableStatus.update { CloudUploadStatus(running = true, total = documents.size, revision = it.revision) }
        uploadJob = viewModelScope.launch {
            try {
                for ((index, uri) in documents.withIndex()) {
                    mutableStatus.update { it.copy(file = "音频 ${index + 1}", stage = "正在读取文件", progress = null) }
                    try {
                        CloudUpload.send(application, accountId, uri) { file, stage, progress ->
                            mutableStatus.update { it.copy(file = file, stage = stage, progress = progress) }
                        }
                        mutableStatus.update { it.copy(completed = it.completed + 1, revision = it.revision + 1) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: CloudUpload.Failure) {
                        failed = failed + uri
                        mutableStatus.update { it.copy(errors = it.errors + "${it.file}：${error.message}") }
                    } catch (_: Exception) {
                        failed = failed + uri
                        mutableStatus.update { it.copy(errors = it.errors + "${it.file}：${it.stage}失败") }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableStatus.update { it.copy(stopped = true) }
                throw cancelled
            } finally {
                mutableStatus.update { it.copy(running = false, file = "", stage = "", progress = null) }
            }
        }
    }

    fun retry(context: Context, accountId: Long) = upload(context, accountId, failed)

    fun cancel() {
        uploadJob?.cancel()
    }
}

private object CloudUpload {
    class Failure(message: String) : IOException(message)

    private const val defaultBucket = "jd-musicrep-privatecloud-audio-public"
    private const val bitrate = "999000"
    private val storageDomains = listOf("127.net", "126.net", "163.com", "163yun.com", "netease.com")

    suspend fun send(
        context: Context,
        accountId: Long,
        uri: Uri,
        status: (file: String, stage: String, percent: Int?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val file = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.take(200)?.takeIf(String::isNotBlank) ?: throw Failure("缺少文件名")
        val type = resolver.getType(uri) ?: "audio/mpeg"
        if (!type.startsWith("audio/")) throw Failure("请选择音频文件")
        val open: () -> InputStream = { resolver.openInputStream(uri) ?: throw Failure("无法读取音频") }
        status(file, "正在读取文件", null)
        val digest = MessageDigest.getInstance("MD5")
        val length = open().use { stream ->
            transfer(stream, check = { ensureActive() }) { bytes, count, _ -> digest.update(bytes, 0, count) }
        }
        if (length == 0L) throw Failure("音频文件为空")
        val md5 = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        val extension = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!extension.matches(Regex("[a-z0-9]{1,10}"))) throw Failure("无法识别音频格式")
        fun checkAccount() {
            ensureActive()
            if (Catalog.account()?.id != accountId) throw Failure("登录账号已更改，请重新打开云盘")
        }

        status(file, "正在核对云盘文件", null)
        checkAccount()
        val check = Catalog.weapi("/weapi/cloud/upload/check", JSONObject()
            .put("bitrate", bitrate).put("ext", "").put("length", length).put("md5", md5)
            .put("songId", "0").put("version", 1))
        val songId = check.optString("songId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Failure("无法识别云盘文件编号")

        status(file, "正在获取上传凭证", null)
        ensureActive()
        val tokenResponse = Catalog.weapi("/weapi/nos/token/alloc", JSONObject()
            .put("bucket", "").put("ext", extension).put("filename", file).put("local", false)
            .put("nos_product", 3).put("type", "audio").put("md5", md5))
        val token = tokenResponse.optJSONObject("result") ?: throw Failure("未收到上传凭证")
        val resourceId = token.optLong("resourceId").takeIf { it > 0 } ?: throw Failure("上传凭证无效")
        if (check.optBoolean("needUpload", true)) {
            val bucket = token.optString("bucket").ifBlank { defaultBucket }
            val objectKey = token.optString("objectKey").takeIf(String::isNotBlank) ?: throw Failure("上传位置无效")
            val credential = token.optString("token").takeIf(String::isNotBlank) ?: throw Failure("上传凭证无效")
            var lastPercent = 0
            status(file, "正在上传音频", 0)
            writeToStorage(bucket, objectKey, credential, type, md5, length, open, { ensureActive() }) { written ->
                val percent = (100.0 * written / length).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    status(file, "正在上传音频", percent)
                }
            }
        }

        status(file, "正在登记云盘歌曲", null)
        checkAccount()
        val tags = readTags(context, uri)
        val info = Catalog.weapi("/weapi/upload/cloud/info/v2", JSONObject()
            .put("md5", md5).put("songid", songId).put("filename", file)
            .put("song", tags.first ?: file.substringBeforeLast('.'))
            .put("artist", tags.second ?: "未知歌手").put("album", tags.third ?: "未知专辑")
            .put("bitrate", bitrate).put("resourceId", resourceId))
        val publishedId = info.optString("songId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Failure("未收到云盘歌曲编号")
        status(file, "正在发布云盘歌曲", null)
        checkAccount()
        Catalog.weapi("/weapi/cloud/pub/v2", JSONObject().put("songid", publishedId))
    }

    private fun readTags(context: Context, uri: Uri): Triple<String?, String?, String?> {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(context, uri)
            fun tag(key: Int) = reader.extractMetadata(key)?.trim()?.takeIf(String::isNotBlank)
            Triple(tag(MediaMetadataRetriever.METADATA_KEY_TITLE), tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                tag(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        } catch (_: Exception) {
            Triple(null, null, null)
        } finally {
            runCatching { reader.release() }
        }
    }

    private fun writeToStorage(
        bucket: String,
        objectKey: String,
        credential: String,
        type: String,
        md5: String,
        length: Long,
        open: () -> InputStream,
        check: () -> Unit,
        progress: (Long) -> Unit,
    ) {
        val discovery = connection("https://wanproxy.127.net/lbs?version=1.0&bucketname=${Uri.encode(bucket)}")
        val address = try {
            val hosts = discovery.inputStream.bufferedReader().use { JSONObject(it.readText()).optJSONArray("upload") }
            (0 until (hosts?.length() ?: 0)).firstNotNullOfOrNull { index ->
                val candidate = runCatching { URL(hosts?.optString(index).orEmpty()) }.getOrNull()
                val domain = candidate?.host?.lowercase(Locale.ROOT).orEmpty()
                domain.takeIf {
                    candidate?.protocol in listOf("http", "https") && candidate?.userInfo == null &&
                        storageDomains.any { allowed -> domain == allowed || domain.endsWith(".$allowed") }
                }
            } ?: throw Failure("没有可用的云盘上传节点")
        } finally {
            discovery.disconnect()
        }
        check()
        // Only the scoped storage credential goes to the HTTPS storage host. Account cookies stay with Catalog.
        val path = "https://$address/${Uri.encode(bucket)}/${Uri.encode(objectKey)}?offset=0&complete=true&version=1.0"
        val upload = connection(path)
        try {
            upload.requestMethod = "POST"
            upload.doOutput = true
            upload.setFixedLengthStreamingMode(length)
            upload.setRequestProperty("Content-Type", type)
            upload.setRequestProperty("Content-MD5", md5)
            upload.setRequestProperty("x-nos-token", credential)
            upload.outputStream.use { output ->
                open().use { stream ->
                    transfer(stream, check) { bytes, count, written ->
                        output.write(bytes, 0, count)
                        progress(written)
                    }
                }
            }
            if (upload.responseCode !in 200..299) throw Failure("云盘服务器拒绝了上传")
            upload.inputStream.close()
        } finally {
            upload.disconnect()
        }
    }

    private fun connection(address: String) = (URL(address).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = false
    }

    private fun transfer(input: InputStream, check: () -> Unit, chunk: (ByteArray, Int, Long) -> Unit): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            chunk(buffer, count, total)
        }
    }
}

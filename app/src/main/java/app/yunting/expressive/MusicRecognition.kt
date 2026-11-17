package app.yunting.expressive

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import kotlin.math.sqrt

private const val FINGERPRINT_RATE = 8_000
private const val RECORD_SECONDS = 6
private const val MATCH_SECONDS = 3

internal class RecognitionError(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun monoFormat(rate: Int) = AudioFormat.Builder()
    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .setSampleRate(rate)
    .build()

@SuppressLint("MissingPermission")
internal suspend fun captureMicrophone(onProgress: (Float) -> Unit): FloatArray =
    captureRecognitionAudio(onProgress) { rate, bufferSize ->
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(monoFormat(rate))
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

internal suspend fun captureRecognitionAudio(
    onProgress: (Float) -> Unit,
    createRecorder: (Int, Int) -> AudioRecord,
): FloatArray = withContext(Dispatchers.IO) {
    val rates = listOf(FINGERPRINT_RATE, 48_000, 44_100, 16_000)
    for (rate in rates) {
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) continue
        val recorder = try {
            createRecorder(rate, maxOf(minimum, rate / 5))
        } catch (error: SecurityException) {
            throw RecognitionError("请先允许录音权限", error)
        } catch (_: IllegalArgumentException) {
            continue
        } catch (_: UnsupportedOperationException) {
            continue
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) continue
            try {
                recorder.startRecording()
            } catch (_: IllegalStateException) {
                continue
            } catch (error: SecurityException) {
                throw RecognitionError("请先允许录音权限", error)
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) continue
            val input = withTimeoutOrNull((RECORD_SECONDS + 4) * 1_000L) {
                readSamples(recorder, rate, onProgress)
            } ?: throw RecognitionError("录音超时，请重试")
            return@withContext downsample(input, rate)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }
    }
    throw RecognitionError("没有可用的录音设备")
}

private suspend fun readSamples(recorder: AudioRecord, rate: Int, onProgress: (Float) -> Unit): ShortArray {
    val samples = ShortArray(rate * RECORD_SECONDS)
    var received = 0
    var updates = 0
    while (received < samples.size) {
        currentCoroutineContext().ensureActive()
        val count = recorder.read(samples, received, minOf(rate / 10, samples.size - received), AudioRecord.READ_NON_BLOCKING)
        if (count < 0) throw RecognitionError("录音中断，请重试")
        if (count == 0) {
            delay(20)
            continue
        }
        received += count
        val step = received * 20 / samples.size
        if (step != updates) {
            updates = step
            withContext(Dispatchers.Main) { onProgress(received.toFloat() / samples.size) }
        }
    }
    return samples
}

private fun downsample(input: ShortArray, rate: Int): FloatArray =
    FloatArray(RECORD_SECONDS * FINGERPRINT_RATE) { index ->
        val from = index * rate / FINGERPRINT_RATE
        val end = (index + 1) * rate / FINGERPRINT_RATE
        var sum = 0
        for (sample in from until end) sum += input[sample]
        sum / ((end - from) * 32_768f)
    }

internal class MusicRecognizer(private val context: Context) {
    suspend fun identify(samples: FloatArray): List<Song> {
        val windowLength = MATCH_SECONDS * FINGERPRINT_RATE
        var heardAudio = false
        val fingerprinter = FingerprintPage(context)
        try {
            for (start in 0..samples.size - windowLength step windowLength) {
                currentCoroutineContext().ensureActive()
                val window = samples.copyOfRange(start, start + windowLength)
                val rms = sqrt(window.sumOf { (it * it).toDouble() } / window.size)
                if (rms < 0.0002) continue
                heardAudio = true
                val gain = (0.1 / rms).coerceIn(1.0, 50.0).toFloat()
                for (index in window.indices) window[index] = (window[index] * gain).coerceIn(-1f, 1f)
                val fingerprint = fingerprinter.make(window)
                val songs = withContext(Dispatchers.IO) { match(fingerprint) }
                if (songs.isNotEmpty()) return songs
            }
            if (!heardAudio) throw RecognitionError("没有听到清晰的声音，请靠近音源再试")
            return emptyList()
        } finally {
            fingerprinter.close()
        }
    }

    private fun match(fingerprint: String): List<Song> {
        val url = Uri.Builder().scheme("https").authority("interface.music.163.com").path("/api/music/audio/match")
            .appendQueryParameter("sessionId", UUID.randomUUID().toString().replace("-", "").take(16))
            .appendQueryParameter("duration", MATCH_SECONDS.toString())
            .appendQueryParameter("rawdata", fingerprint)
            .appendQueryParameter("algorithmCode", "shazam_v2")
            .appendQueryParameter("times", "1")
            .appendQueryParameter("decrypt", "1")
            .build()
        val connection = URL(url.toString()).openConnection() as HttpURLConnection
        val response = try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "https://music.163.com/")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException()
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (error: Exception) {
            throw RecognitionError("暂时无法连接网易云识曲服务", error)
        } finally {
            connection.disconnect()
        }
        if (response.optInt("code") != 200) throw RecognitionError("网易云识曲服务暂不可用")
        val matches = response.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        val songs = JSONArray()
        for (index in 0 until matches.length()) matches.optJSONObject(index)?.optJSONObject("song")?.let(songs::put)
        return Catalog.songs(songs).distinctBy(Song::id).take(3)
    }
}

private const val AFP_PAGE = "file:///android_asset/audio-fingerprint/index.html"

private class FingerprintPage(private val context: Context) {
    private var webView: WebView? = null
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var pending: CompletableDeferred<String>? = null

    suspend fun make(samples: FloatArray): String {
        withContext(Dispatchers.Main) { if (webView == null) open() }
        withTimeoutOrNull(10_000L) { loaded.await() } ?: throw RecognitionError("听歌识曲加载超时")
        val encoded = withContext(Dispatchers.Default) {
            val data = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            data.asFloatBuffer().put(samples)
            Base64.getEncoder().encodeToString(data.array())
        }
        val result = CompletableDeferred<String>()
        pending = result
        return try {
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript("makeFingerprint(1,'$encoded')", null)
            }
            withTimeoutOrNull(10_000L) { result.await() }?.takeIf(String::isNotBlank)
                ?: throw RecognitionError("无法计算歌曲指纹，请重试")
        } finally {
            pending = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun open() {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url == AFP_PAGE) loaded.complete(Unit)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        view.addJavascriptInterface(object {
            @JavascriptInterface
            fun finish(requestId: Int, fingerprint: String) {
                if (requestId == 1) pending?.complete(fingerprint)
            }
        }, "AudioFingerprint")
        webView = view
        view.loadUrl(AFP_PAGE)
    }

    suspend fun close() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("AudioFingerprint")
            destroy()
        }
        webView = null
    }
}

package app.yunting.expressive

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val CAPTURE_CHANNEL = "music_identification"
private const val CAPTURE_NOTIFICATION = 4129

@RequiresApi(Build.VERSION_CODES.Q)
class RecognitionCaptureService : Service() {
    internal inner class CaptureBinding : Binder() {
        suspend fun capture(grant: Intent, progress: (Float) -> Unit): FloatArray =
            capturePlayback(grant, progress)
    }

    override fun onBind(intent: Intent?): IBinder = CaptureBinding()

    @SuppressLint("MissingPermission")
    private suspend fun capturePlayback(grant: Intent, progress: (Float) -> Unit): FloatArray = coroutineScope {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CAPTURE_CHANNEL, "听歌识曲", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            CAPTURE_NOTIFICATION,
            Notification.Builder(this@RecognitionCaptureService, CAPTURE_CHANNEL)
                .setContentTitle("正在识别设备声音")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        var projection: MediaProjection? = null
        val stopped = CompletableDeferred<Unit>()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopped.complete(Unit)
            }
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val capture = manager.getMediaProjection(Activity.RESULT_OK, grant)
                ?: throw RecognitionError("系统未授予设备音频访问权限")
            projection = capture
            capture.registerCallback(callback, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(capture)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val recordTask = async {
                captureRecognitionAudio(progress) { rate, bufferSize ->
                    AudioRecord.Builder()
                        .setAudioFormat(monoFormat(rate))
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                }
            }
            try {
                select {
                    recordTask.onAwait { it }
                    stopped.onAwait { throw RecognitionError("设备音频采集已由系统停止") }
                }
            } finally {
                recordTask.cancel()
            }
        } finally {
            projection?.unregisterCallback(callback)
            projection?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun capturePlayback(
    context: Context,
    grant: Intent,
    progress: (Float) -> Unit,
): FloatArray = withContext(Dispatchers.Main) {
    val service = CompletableDeferred<RecognitionCaptureService.CaptureBinding>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val capture = binder as? RecognitionCaptureService.CaptureBinding
            if (capture == null) service.completeExceptionally(RecognitionError("设备音频服务不可用"))
            else service.complete(capture)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service.completeExceptionally(RecognitionError("设备音频服务已断开"))
        }
    }
    val bound = context.bindService(
        Intent(context, RecognitionCaptureService::class.java),
        connection,
        Context.BIND_AUTO_CREATE,
    )
    if (!bound) throw RecognitionError("无法启动设备音频采集")
    try {
        val capture = withTimeoutOrNull(4_000L) { service.await() }
            ?: throw RecognitionError("设备音频服务连接超时")
        capture.capture(grant, progress)
    } finally {
        context.unbindService(connection)
    }
}

package com.juangema.dynamicvt2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Records the current Android display to an MP4 in Movies/Dynamic VT-2D.
 *
 * MediaProjection captures the composed display, not a per-window scene. The
 * service deliberately does not promise selective overlay exclusion: Android
 * exposes no general public API to keep a window visible locally but remove
 * only that window from this recording.
 */
class ScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var recording = false
    private var finalizing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recording && !finalizing) stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recording || projection != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "La grabación integrada requiere Android 10 o posterior.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            Toast.makeText(this, "Android no entregó permiso para grabar la pantalla.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android no pudo crear la sesión de captura.")
            projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val scale = minOf(1f, 1920f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
            require(width >= 2 && height >= 2) { "Resolución de pantalla no válida." }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DynamicVT2D_" + System.currentTimeMillis() + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Dynamic VT-2D")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el archivo en la galería.")
            outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: error("No se pudo abrir el archivo de video.")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setOutputFile(outputDescriptor!!.fileDescriptor)
            recorder.prepare()

            virtualDisplay = projection?.createVirtualDisplay(
                "Dynamic-VT-2D recording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            ) ?: error("No se pudo crear la pantalla virtual de grabación.")
            recorder.start()
            recording = true
            Toast.makeText(this, "Grabación iniciada. Usa Detener y guardar para finalizar el MP4.", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            Toast.makeText(this, "Error al iniciar la grabación: " + (error.localizedMessage ?: "desconocido"), Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (finalizing) return
        finalizing = true
        var saved = false
        if (recording) {
            saved = runCatching {
                mediaRecorder?.stop()
                true
            }.getOrDefault(false)
        }
        recording = false

        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { outputDescriptor?.close() }
        outputDescriptor = null
        runCatching { projection?.stop() }
        projection = null

        val uri = outputUri
        if (uri != null) {
            if (saved) {
                runCatching {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                }
                Toast.makeText(this, "Video guardado en Películas/Dynamic VT-2D.", Toast.LENGTH_LONG).show()
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(this, "La grabación no produjo un video válido; se descartó el archivo incompleto.", Toast.LENGTH_LONG).show()
            }
        }
        outputUri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Grabación Dynamic VT-2D", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP)
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Dynamic VT-2D está grabando")
            .setContentText("Pulsa Detener para guardar el MP4")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPending)
            .build()
    }

    override fun onDestroy() {
        if (!finalizing) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.juangema.dynamicvt2d.RECORD_START"
        const val ACTION_STOP = "com.juangema.dynamicvt2d.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "dynamic_vt2d_recording"
        private const val NOTIFICATION_ID = 2208
    }
}

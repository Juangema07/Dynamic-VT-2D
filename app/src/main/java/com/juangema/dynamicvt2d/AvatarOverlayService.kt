package com.juangema.dynamicvt2d

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.sqrt
import kotlin.random.Random
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AvatarOverlayService : LifecycleService() {
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var imageView: ImageView? = null
    private var fallbackView: TextView? = null
    private var menuView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var blinkClosed = false
    private var selectedFrames: List<String> = emptyList()
    private var mouthOpen = false
    private var avatarSizePx = 0
    private var avatarOpacity = 1f
    private var avatarRotation = 0f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var trackingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL).build()
    )
    private var lastFaceState = false
    private var menuDismissed = false
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        prefs().edit().putBoolean("recording_mode", !recordingMode()).apply()
        menuDismissed = true
        updateMenuVisibility()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private fun recordingMode() = prefs().getBoolean("recording_mode", false)
    private fun mouthMode() = (prefs().getString("mouth_mode", "VOICE") ?: "VOICE").uppercase()
    private fun blinkMode() = (prefs().getString("blink_mode", "AUTO") ?: "AUTO").uppercase()

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (blinkMode() == "AUTO") {
                blinkClosed = true
                renderFrame()
                handler.postDelayed({
                    blinkClosed = false
                    renderFrame()
                    handler.postDelayed(this, Random.nextLong(1800L, 4200L))
                }, 130L)
            } else {
                handler.postDelayed(this, 900L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        configureAndShow()
        return Service.START_NOT_STICKY
    }

    private fun startTrackingForegroundIfNeeded() {
        val needsMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needsCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!needsMic && !needsCamera) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Dynamic VT-2D", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Dynamic VT-2D activo")
                .setContentText("Seguimiento del avatar en funcionamiento")
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Dynamic VT-2D activo").setOngoing(true).build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val type = (if (needsMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (needsCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun configureAndShow() {
        val expression = AvatarStore.selectedExpression(this)
        selectedFrames = expression?.frames ?: emptyList()
        avatarSizePx = (AvatarStore.size(this) * resources.displayMetrics.density).toInt()
        avatarOpacity = AvatarStore.opacity(this).coerceIn(0.2f, 1f)
        avatarRotation = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("avatar_rotation", 0f)
        startTrackingForegroundIfNeeded()

        if (rootView == null) {
            val root = FrameLayout(this)
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Avatar flotante Dynamic VT-2D"
            }
            val fallback = TextView(this).apply {
                text = "🙂"; textSize = 68f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            root.addView(fallback, FrameLayout.LayoutParams(-1, -1))
            rootView = root; imageView = image; fallbackView = fallback
            root.setOnTouchListener { _, event -> handleTouch(event) }
            val params = WindowManager.LayoutParams(
                avatarSizePx, avatarSizePx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs().getInt("overlay_x", 40); y = prefs().getInt("overlay_y", 180)
            }
            layoutParams = params
            windowManager?.addView(root, params)
        } else {
            layoutParams?.let { it.width = avatarSizePx; it.height = avatarSizePx
                runCatching { windowManager?.updateViewLayout(rootView, it) } }
        }
        rootView?.alpha = avatarOpacity
        rootView?.rotation = AvatarStore.rotation(this)
        buildMenu()
        renderFrame()
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, Random.nextLong(1800L, 4000L))
        restartTracking()
        updateMenuVisibility()
    }

    private fun buildMenu() {
        if (menuView != null) return
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 22, 27, 42))
                cornerRadius = 18f
                setStroke(1, Color.rgb(104, 127, 190))
            }
        }
        fun addAction(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false
                setOnClickListener { action(); updateMenuLabels(); renderFrame(); updateMenuVisibility() }
            }
            menu.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        addAction("Siguiente expresión") { cycleExpression(1) }
        addAction("Tamaño +") { adjustSize(12f) }
        addAction("Tamaño −") { adjustSize(-12f) }
        addAction("Rotar +15°") { adjustRotation(15f) }
        addAction("Rotar −15°") { adjustRotation(-15f) }
        addAction("Opacidad +") { adjustOpacity(0.1f) }
        addAction("Opacidad −") { adjustOpacity(-0.1f) }
        addAction("Modo grabación: activar/desactivar") {
            prefs().edit().putBoolean("recording_mode", !recordingMode()).apply()
        }
        addAction("Boca: voz / cámara") {
            prefs().edit().putString("mouth_mode", if (mouthMode() == "VOICE") "CAMERA" else "VOICE").apply()
            restartTracking()
        }
        addAction("Parpadeo: automático / cámara") {
            prefs().edit().putString("blink_mode", if (blinkMode() == "AUTO") "CAMERA" else "AUTO").apply()
            restartTracking()
        }
        addAction("Tamaño +") { changeAvatarSize(12f) }
        addAction("Tamaño −") { changeAvatarSize(-12f) }
        addAction("Rotar −15°") { rotateAvatar(-15f) }
        addAction("Rotar +15°") { rotateAvatar(15f) }
        addAction("Opacidad: alternar") {
            avatarOpacity = if (avatarOpacity > 0.7f) 0.55f else 1f
            AvatarStore.setOpacity(this, avatarOpacity)
            rootView?.alpha = avatarOpacity
        }
        addAction("Ocultar controles") { menuDismissed = true; hideMenu() }
        menuView = menu
        val params = WindowManager.LayoutParams(
            (avatarSizePx * 1.2f).toInt().coerceAtLeast(240), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        menuParams = params
        windowManager?.addView(menu, params)
        updateMenuLabels()
    }

    private fun updateMenuLabels() {
        val menu = menuView ?: return
        val buttons = (0 until menu.childCount).mapNotNull { menu.getChildAt(it) as? Button }
        if (buttons.size >= 10) {
            buttons[7].text = if (recordingMode()) "Modo grabación: ACTIVADO" else "Modo grabación: desactivado"
            buttons[8].text = "Boca: " + if (mouthMode() == "VOICE") "voz" else "cámara"
            buttons[9].text = "Parpadeo: " + if (blinkMode() == "AUTO") "automático" else "cámara"
        }
    }

    private fun updateMenuVisibility() {
        val menu = menuView ?: return
        val show = !recordingMode() && !menuDismissed
        if (show && menu.parent == null) {
            runCatching { windowManager?.addView(menu, menuParams) }
        }
        menu.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val p = menuParams
            val a = layoutParams
            if (p != null && a != null) {
                p.x = a.x
                p.y = a.y + avatarSizePx + 8
                runCatching { windowManager?.updateViewLayout(menu, p) }
            }
        }
        updateMenuLabels()
    }

    private fun hideMenu() {
        menuView?.visibility = View.GONE
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                longPressTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 650L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (kotlin.math.abs(dx) > 18 || kotlin.math.abs(dy) > 18) handler.removeCallbacks(longPressRunnable)
                if (!recordingMode()) {
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(rootView, params) }
                    updateMenuVisibility()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (longPressTriggered) {
                    menuDismissed = true
                    updateMenuVisibility()
                } else if (kotlin.math.abs(dx) < 12 && kotlin.math.abs(dy) < 12) {
                    if (recordingMode()) cycleExpression(1) else {
                        val menu = menuView
                        if (menu?.visibility == View.VISIBLE) hideMenu() else {
                            menuDismissed = false
                            updateMenuVisibility()
                        }
                    }
                } else if (recordingMode()) {
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) cycleExpression(if (dx < 0) 1 else -1)
                    else {
                        val newSize = (AvatarStore.size(this) + if (dy < 0) 12f else -12f).coerceIn(90f, 320f)
                        AvatarStore.setSize(this, newSize)
                        avatarSizePx = (newSize * resources.displayMetrics.density).toInt()
                        params.width = avatarSizePx; params.height = avatarSizePx
                        runCatching { windowManager?.updateViewLayout(rootView, params) }
                    }
                } else {
                    prefs().edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
                }
                return true
            }
        }
        return false
    }

    private fun adjustSize(delta: Float) {
        val size = (AvatarStore.size(this) + delta).coerceIn(90f, 320f)
        AvatarStore.setSize(this, size)
        avatarSizePx = (size * resources.displayMetrics.density).toInt()
        layoutParams?.let { it.width = avatarSizePx; it.height = avatarSizePx; runCatching { windowManager?.updateViewLayout(rootView, it) } }
        updateMenuVisibility()
    }

    private fun adjustRotation(delta: Float) {
        val rotation = ((AvatarStore.rotation(this) + delta + 180f) % 360f) - 180f
        AvatarStore.setRotation(this, rotation)
        imageView?.rotation = rotation
    }

    private fun adjustOpacity(delta: Float) {
        avatarOpacity = (AvatarStore.opacity(this) + delta).coerceIn(0.2f, 1f)
        AvatarStore.setOpacity(this, avatarOpacity)
        rootView?.alpha = avatarOpacity
    }

    private fun cycleExpression(direction: Int) {
        val expressions = AvatarStore.load(this)
        if (expressions.isEmpty()) return
        val current = expressions.indexOfFirst { it.name == AvatarStore.selected(this) }.let { if (it < 0) 0 else it }
        val next = (current + direction + expressions.size) % expressions.size
        AvatarStore.select(this, expressions[next].name)
        selectedFrames = expressions[next].frames
        blinkClosed = false; mouthOpen = false
        renderFrame()
    }

    private fun changeAvatarSize(delta: Float) {
        val next = (AvatarStore.size(this) + delta).coerceIn(90f, 320f)
        AvatarStore.setSize(this, next)
        avatarSizePx = (next * resources.displayMetrics.density).toInt()
        layoutParams?.let {
            it.width = avatarSizePx
            it.height = avatarSizePx
            runCatching { windowManager?.updateViewLayout(rootView, it) }
        }
        updateMenuVisibility()
    }

    private fun rotateAvatar(delta: Float) {
        avatarRotation = (avatarRotation + delta) % 360f
        AvatarStore.setRotation(this, avatarRotation)
        rootView?.rotation = avatarRotation
    }

    private fun restartTracking() {
        stopAudioTracking()
        cameraProvider?.unbindAll()
        cameraProvider = null
        val needCamera = mouthMode() == "CAMERA" || blinkMode() == "CAMERA"
        if (needCamera && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCameraTracking()
        if (mouthMode() == "VOICE" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAudioTracking()
        startTrackingForegroundIfNeeded()
    }

    private fun startAudioTracking() {
        if (trackingThread?.isAlive == true) return
        trackingThread = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@Thread
            val bufferSize = maxOf(min, 2048)
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            }.getOrNull() ?: return@Thread
            audioRecord = recorder
            try {
                recorder.startRecording()
                val buffer = ShortArray(bufferSize / 2)
                var noiseFloor = 500.0
                while (!Thread.currentThread().isInterrupted && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i].toDouble()
                        val rms = sqrt(sum / read)
                        noiseFloor = if (rms < noiseFloor * 2) noiseFloor * 0.98 + rms * 0.02 else noiseFloor
                        val open = rms > maxOf(900.0, noiseFloor * 1.8)
                        if (open != mouthOpen) handler.post { mouthOpen = open; renderFrame() }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { recorder.stop() }; recorder.release()
            }
        }.apply { name = "DynamicVT2D-Audio"; start() }
    }

    private fun stopAudioTracking() {
        trackingThread?.interrupt()
        trackingThread = null
        runCatching { audioRecord?.stop() }
        audioRecord = null
        mouthOpen = false
    }

    private fun startCameraTracking() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy -> analyzeFace(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFace(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    if (lastFaceState) { lastFaceState = false; mouthOpen = false; blinkClosed = false; renderFrame() }
                } else {
                    val upper = face.getContour(com.google.mlkit.vision.face.FaceContour.UPPER_LIP_TOP)?.points
                    val lower = face.getContour(com.google.mlkit.vision.face.FaceContour.LOWER_LIP_BOTTOM)?.points
                    if (upper != null && lower != null && upper.isNotEmpty() && lower.isNotEmpty()) {
                        val upperY = upper.map { it.y }.average()
                        val lowerY = lower.map { it.y }.average()
                        val open = (lowerY - upperY) / face.boundingBox.height().coerceAtLeast(1) > 0.055
                        if (mouthMode() == "CAMERA" && open != mouthOpen) { mouthOpen = open; renderFrame() }
                    }
                    val leftEye = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYE)?.points
                    val rightEye = face.getContour(com.google.mlkit.vision.face.FaceContour.RIGHT_EYE)?.points
                    if (blinkMode() == "CAMERA" && leftEye != null && rightEye != null && leftEye.isNotEmpty() && rightEye.isNotEmpty()) {
                        val eyeSpan = (leftEye.maxOf { it.y } - leftEye.minOf { it.y } + rightEye.maxOf { it.y } - rightEye.minOf { it.y }) / 2f
                        val closed = eyeSpan / face.boundingBox.height().coerceAtLeast(1) < 0.018
                        if (closed != blinkClosed) { blinkClosed = closed; renderFrame() }
                    }
                    lastFaceState = true
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun startVoiceForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("dynamic_vt2d_mic", "Dynamic VT-2D", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, "dynamic_vt2d_mic")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Dynamic VT-2D activo")
            .setContentText("El micrófono detecta el nivel de voz para animar la boca.")
            .setOngoing(true)
            .build()
        runCatching { startForeground(7321, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
    }

    private fun renderFrame() {
        val frameIndex = (if (blinkClosed) 2 else 0) + if (mouthOpen) 1 else 0
        val uriText = selectedFrames.getOrNull(frameIndex)
        val bitmap = uriText?.let { text ->
            runCatching { contentResolver.openInputStream(Uri.parse(text))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (bitmap != null) {
            imageView?.setImageBitmap(bitmap); imageView?.visibility = View.VISIBLE; fallbackView?.visibility = View.GONE
        } else {
            imageView?.setImageDrawable(null); imageView?.visibility = View.GONE; fallbackView?.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutParams?.let {
            runCatching { windowManager?.updateViewLayout(rootView, it) }
            updateMenuVisibility()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAudioTracking()
        runCatching { cameraProvider?.unbindAll() }
        faceDetector.close()
        menuView?.let { runCatching { windowManager?.removeView(it) } }
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null; imageView = null; fallbackView = null; menuView = null
        layoutParams = null; menuParams = null; windowManager = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_STOP = "com.juangema.dynamicvt2d.STOP_OVERLAY"
        private const val PREFS = "dynamic_vt2d"
        private const val CHANNEL_ID = "dynamic_vt2d_tracking"
        private const val NOTIFICATION_ID = 27
    }
}

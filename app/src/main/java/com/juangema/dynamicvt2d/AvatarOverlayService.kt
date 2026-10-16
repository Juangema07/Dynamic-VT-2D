package com.juangema.dynamicvt2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shows the avatar and operator panel as overlay windows.
 *
 * FLAG_SECURE is intentionally not applied to the panel: Android may render a
 * secure overlay as a black rectangle in screen recordings. With third-party
 * recorders, Android does not expose a reliable public API that hides only this
 * window from capture while keeping it visible to the local user.
 */
class AvatarOverlayService : LifecycleService() {
    private var windowManager: WindowManager? = null

    private var avatarView: FrameLayout? = null
    private var avatarImage: ImageView? = null
    private var fallbackView: TextView? = null
    private var effectView: TextView? = null
    private var avatarParams: WindowManager.LayoutParams? = null

    private var menuView: FrameLayout? = null
    private var menuContent: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuVisible = false
    private var menuPage = PAGE_EXPRESSIONS
    private var circularMode = false

    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceDetector: FaceDetector? = null
    private var cameraEnabled = false
    private var cameraStartInFlight = false
    private var cameraStartGeneration = 0
    private var cameraEyesClosed = false
    private var cameraMouthOpen = false
    private var faceSmileActive = false
    private var closedEyeSamples = 0
    private var openEyeSamples = 0
    private var openMouthSamples = 0
    private var closedMouthSamples = 0
    private var lastFaceSeenAt = 0L
    private var voiceLevelActive = false
    private var isSpeakingAnimated = false
    private var idleOffset = 0f

    private var selectedFrames: List<String> = emptyList()
    private var avatarOpacity = 1f
    private var locked = false
    private var blinkClosed = false
    private var voiceMouthOpen = false
    private var lastRenderedKey = ""
    // Access-ordered and bounded so switching through many custom expressions does not retain every PNG in memory.
    private val bitmapCache = LinkedHashMap<String, Bitmap?>(16, 0.75f, true)

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var movedDuringTouch = false
    private var lastTapTime = 0L
    private var avatarSizePx = 0
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var configurationReceiverRegistered = false

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                handler.postDelayed({ repositionOverlaysForCurrentDisplay() }, 180L)
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListeningRequested = false
    private var voiceRestartScheduled = false

    private val voiceRestartRunnable = Runnable {
        voiceRestartScheduled = false
        if (voiceListeningRequested && speechRecognizer == null) startVoiceRecognition(showListeningToast = false)
    }

    private fun scheduleVoiceRecognitionRestart() {
        if (!voiceListeningRequested || voiceRestartScheduled) return
        voiceRestartScheduled = true
        handler.postDelayed(voiceRestartRunnable, 350L)
    }

    private val idleMotionRunnable = object : Runnable {
        override fun run() {
            val view = avatarView ?: return
            if (!isSpeakingAnimated && !locked) {
                view.animate().cancel()
                view.animate().translationY(dp(-3).toFloat()).setDuration(900L).withEndAction {
                    view.animate().translationY(dp(2).toFloat()).setDuration(1100L).withEndAction {
                        if (avatarView != null) handler.postDelayed(this, 80L)
                    }.start()
                }.start()
            } else {
                handler.postDelayed(this, 350L)
            }
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!cameraEnabled) {
                blinkClosed = true
                renderFrame()
                handler.postDelayed({
                    blinkClosed = false
                    renderFrame()
                    handler.postDelayed(this, Random.nextLong(2200L, 4800L))
                }, 130L)
            } else {
                handler.postDelayed(this, Random.nextLong(2500L, 5000L))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lastOrientation = resources.configuration.orientation
        runCatching {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(configurationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configurationReceiver, filter)
            }
            configurationReceiverRegistered = true
        }
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(options)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        startForegroundCompat()
        configureAndShow()
        if (!cameraEnabled && !cameraStartInFlight) startCameraTracking()
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, Random.nextLong(2200L, 4800L))
        handler.removeCallbacks(idleMotionRunnable)
        handler.postDelayed(idleMotionRunnable, 1200L)
        return Service.START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "dynamic_vt2d_live"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Dynamic VT-2D LIVE", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Dynamic VT-2D está activo")
            .setContentText("Avatar flotante y seguimiento facial")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun configureAndShow() {
        selectedFrames = AvatarStore.selectedExpression(this)?.frames ?: emptyList()
        avatarSizePx = dp(AvatarStore.size(this).toInt())
        avatarOpacity = AvatarStore.opacity(this).coerceIn(0.2f, 1f)
        locked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LOCKED, false)

        if (avatarView == null) {
            val root = FrameLayout(this)
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Avatar flotante Dynamic VT-2D"
            }
            val fallback = TextView(this).apply {
                text = "🙂"
                textSize = 68f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            root.addView(fallback, FrameLayout.LayoutParams(-1, -1))
            val effects = TextView(this).apply {
                text = ""
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(dp(3).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
                visibility = View.GONE
                elevation = dp(10).toFloat()
            }
            root.addView(effects, FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            effectView = effects
            root.setOnTouchListener { _, event -> handleAvatarTouch(event) }
            avatarView = root
            avatarImage = image
            fallbackView = fallback

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val params = WindowManager.LayoutParams(
                avatarSizePx,
                avatarSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("overlay_x", dp(24))
                y = prefs.getInt("overlay_y", dp(140))
            }
            avatarParams = params
            runCatching { windowManager?.addView(root, params) }
                .onFailure {
                    Toast.makeText(this, "No se pudo mostrar el avatar. Revisa el permiso de superposición.", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return
                }
        } else {
            avatarParams?.let { params ->
                params.width = avatarSizePx
                params.height = avatarSizePx
                runCatching { windowManager?.updateViewLayout(avatarView, params) }
            }
        }

        applyAvatarAppearance()
        renderFrame()
        if (menuVisible) repositionMenuNearAvatar()
    }

    private fun displaySize(): Point {
        val point = Point()
        runCatching {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
        }
        if (point.x <= 0 || point.y <= 0) {
            point.x = resources.displayMetrics.widthPixels
            point.y = resources.displayMetrics.heightPixels
        }
        return point
    }

    private fun repositionOverlaysForCurrentDisplay() {
        val wm = windowManager ?: return
        val avatar = avatarParams ?: return
        val size = displaySize()
        val oldOrientation = lastOrientation
        lastOrientation = resources.configuration.orientation

        // Clamp the saved overlay position to the new display bounds after rotation.
        val maxX = (size.x - avatar.width).coerceAtLeast(0)
        val maxY = (size.y - avatar.height).coerceAtLeast(0)
        avatar.x = avatar.x.coerceIn(0, maxX)
        avatar.y = avatar.y.coerceIn(0, maxY)
        runCatching { avatarView?.let { wm.updateViewLayout(it, avatar) } }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("overlay_x", avatar.x).putInt("overlay_y", avatar.y).apply()

        if (menuVisible) repositionMenuNearAvatar()
    }

    private fun repositionMenuNearAvatar() {
        val params = menuParams ?: return
        val wm = windowManager ?: return
        val avatar = avatarParams ?: return
        val size = displaySize()
        val width = params.width
        val height = params.height
        val centerX = avatar.x + avatar.width / 2
        val centerY = avatar.y + avatar.height / 2
        val gap = dp(8)
        val maxX = (size.x - width).coerceAtLeast(0)
        val maxY = (size.y - height).coerceAtLeast(0)

        // Prefer below/right of the avatar; flip to the other side when there is no room.
        params.x = when {
            centerX + width / 2 <= size.x - dp(8) -> (centerX - width / 2).coerceIn(0, maxX)
            else -> (centerX - width / 2).coerceIn(0, maxX)
        }
        params.y = if (centerY + height / 2 + gap <= size.y) {
            (avatar.y + avatar.height + gap).coerceIn(0, maxY)
        } else {
            (avatar.y - height - gap).coerceIn(0, maxY)
        }
        runCatching { menuView?.let { wm.updateViewLayout(it, params) } }
    }

    private fun handleAvatarTouch(event: MotionEvent): Boolean {
        val params = avatarParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                movedDuringTouch = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (!locked && (abs(dx) > dp(7) || abs(dy) > dp(7))) movedDuringTouch = true
                if (!locked && movedDuringTouch) {
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(avatarView, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (movedDuringTouch && !locked) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("overlay_x", params.x)
                        .putInt("overlay_y", params.y)
                        .apply()
                    lastTapTime = 0L
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapTime in 1..DOUBLE_TAP_MS) {
                        lastTapTime = 0L
                        toggleMenu()
                    } else {
                        lastTapTime = now
                    }
                }
                return true
            }
        }
        return true
    }

    private fun applyAvatarAppearance() {
        // Locking disables dragging only; it must never fade the avatar in recordings.
        avatarView?.alpha = avatarOpacity.coerceIn(0.2f, 1f)
    }

    private fun setSpeakingMotion(speaking: Boolean) {
        if (speaking == isSpeakingAnimated) return
        isSpeakingAnimated = speaking
        val view = avatarView ?: return
        view.animate().cancel()
        if (speaking) {
            view.animate().translationY(dp(-8).toFloat()).setDuration(110L).withEndAction {
                view.animate().translationY(0f).setDuration(170L).start()
            }.start()
        } else {
            view.animate().translationY(0f).setDuration(180L).withEndAction {
                handler.removeCallbacks(idleMotionRunnable)
                handler.postDelayed(idleMotionRunnable, 500L)
            }.start()
        }
    }

    private fun playHitEffect() {
        val effect = effectView ?: return
        effect.animate().cancel()
        effect.text = "💥  🐦  🐦  🐦"
        effect.visibility = View.VISIBLE
        effect.alpha = 0f
        effect.rotation = -12f
        effect.scaleX = 0.35f
        effect.scaleY = 0.35f
        effect.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).rotation(12f).setDuration(180L)
            .withEndAction {
                effect.animate().rotationBy(350f).scaleX(0.9f).scaleY(0.9f).setDuration(850L)
                    .withEndAction {
                        effect.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(220L)
                            .withEndAction { effect.visibility = View.GONE }.start()
                    }.start()
            }.start()
    }

    private fun renderFrame() {
        val eyeClosed = if (cameraEnabled) cameraEyesClosed else blinkClosed
        val mouthIsOpen = cameraMouthOpen || voiceMouthOpen
        val index = (if (eyeClosed) 2 else 0) + if (mouthIsOpen) 1 else 0
        val uriText = selectedFrames.getOrNull(index)
        val renderKey = "${uriText.orEmpty()}|$index"
        if (renderKey == lastRenderedKey) return
        lastRenderedKey = renderKey

        val bitmap = uriText?.let { key ->
            if (bitmapCache.containsKey(key)) {
                bitmapCache[key]
            } else {
                val decoded = runCatching {
                    contentResolver.openInputStream(Uri.parse(key))?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                bitmapCache[key] = decoded
                while (bitmapCache.size > MAX_CACHED_BITMAPS) {
                    val oldestKey = bitmapCache.keys.firstOrNull() ?: break
                    bitmapCache.remove(oldestKey)
                }
                decoded
            }
        }
        if (bitmap != null) {
            avatarImage?.setImageBitmap(bitmap)
            avatarImage?.visibility = View.VISIBLE
            fallbackView?.visibility = View.GONE
        } else {
            avatarImage?.setImageDrawable(null)
            avatarImage?.visibility = View.GONE
            fallbackView?.visibility = View.VISIBLE
        }
    }

    private fun startCameraTracking() {
        if (cameraEnabled || cameraStartInFlight) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Concede el permiso de cámara desde el estudio y reinicia LIVE.", Toast.LENGTH_LONG).show()
            return
        }
        cameraStartInFlight = true
        val startGeneration = ++cameraStartGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // A stop command may arrive while CameraX is still initializing.
            // Ignore stale callbacks so they cannot unexpectedly restart the camera.
            if (startGeneration != cameraStartGeneration || !cameraStartInFlight) return@addListener
            runCatching {
                val provider = future.get()
                if (startGeneration != cameraStartGeneration || !cameraStartInFlight) return@runCatching
                provider.unbindAll()
                // CameraX must know the current display rotation. Without this, the frame's
                // rotationDegrees can remain at portrait after the device rotates to landscape.
                val displayRotation = avatarView?.display?.rotation
                    ?: @Suppress("DEPRECATION") windowManager?.defaultDisplay?.rotation
                    ?: Surface.ROTATION_0
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(displayRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeImage(proxy) }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                cameraProvider = provider
                cameraEnabled = true
                cameraStartInFlight = false
                cameraEyesClosed = false
                cameraMouthOpen = false
                renderFrame()
                if (menuVisible) updateMenuContent()
            }.onFailure {
                cameraStartInFlight = false
                cameraEnabled = false
                Toast.makeText(this, "No se pudo iniciar la cámara frontal. Cierra otra app que la esté usando e inténtalo de nuevo.", Toast.LENGTH_LONG).show()
                if (menuVisible) updateMenuContent()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val input = runCatching {
            InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        }.getOrElse {
            proxy.close()
            return
        }

        val detector = faceDetector
        if (detector == null) {
            proxy.close()
            return
        }
        detector.process(input)
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { faces ->
                // Ignore late detector results after the user has switched tracking off.
                if (!cameraEnabled) return@addOnSuccessListener
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face != null) {
                    val leftOpen = face.leftEyeOpenProbability ?: 1f
                    val rightOpen = face.rightEyeOpenProbability ?: 1f
                    lastFaceSeenAt = SystemClock.elapsedRealtime()
                    val eyesLookClosed = leftOpen < EYE_CLOSED_THRESHOLD && rightOpen < EYE_CLOSED_THRESHOLD
                    if (eyesLookClosed) {
                        closedEyeSamples++
                        openEyeSamples = 0
                    } else {
                        openEyeSamples++
                        closedEyeSamples = 0
                    }
                    val closed = when {
                        closedEyeSamples >= EYE_CLOSE_CONFIRM_FRAMES -> true
                        openEyeSamples >= EYE_OPEN_CONFIRM_FRAMES -> false
                        else -> cameraEyesClosed
                    }

                    val upperLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                    val lowerLip = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
                    val mouthGap = if (!upperLip.isNullOrEmpty() && !lowerLip.isNullOrEmpty()) {
                        val upperY = upperLip.map { it.y.toDouble() }.average()
                        val lowerY = lowerLip.map { it.y.toDouble() }.average()
                        (lowerY - upperY) / face.boundingBox.height().coerceAtLeast(1)
                    } else 0.0
                    val mouthOpenNow = mouthGap > MOUTH_OPEN_THRESHOLD
                    if (mouthOpenNow) {
                        openMouthSamples++
                        closedMouthSamples = 0
                    } else {
                        closedMouthSamples++
                        openMouthSamples = 0
                    }
                    val mouthOpen = when {
                        openMouthSamples >= MOUTH_OPEN_CONFIRM_FRAMES -> true
                        closedMouthSamples >= MOUTH_CLOSE_CONFIRM_FRAMES -> false
                        else -> cameraMouthOpen
                    }

                    if (closed != cameraEyesClosed || mouthOpen != cameraMouthOpen) {
                        cameraEyesClosed = closed
                        cameraMouthOpen = mouthOpen
                        renderFrame()
                    }
                    updateSmileExpression(face)
                }
            }
            .addOnCompleteListener {
                proxy.close()
                handler.post {
                    if (cameraEnabled && SystemClock.elapsedRealtime() - lastFaceSeenAt > FACE_LOST_RESET_MS) {
                        closedEyeSamples = 0
                        openEyeSamples = 0
                        openMouthSamples = 0
                        closedMouthSamples = 0
                        val changed = cameraEyesClosed || cameraMouthOpen
                        cameraEyesClosed = false
                        cameraMouthOpen = false
                        faceSmileActive = false
                        if (changed) renderFrame()
                    }
                }
            }
    }

    private fun updateSmileExpression(face: Face) {
        val smileProbability = face.smilingProbability ?: 0f
        val expressions = AvatarStore.load(this)
        if (smileProbability >= SMILE_ON_THRESHOLD && !faceSmileActive) {
            val happy = expressions.firstOrNull {
                val n = normalize(it.name)
                n.contains("feliz") || n.contains("alegre") || n.contains("sonrisa") || n.contains("happy")
            }
            if (happy != null) {
                faceSmileActive = true
                selectExpression(happy)
            }
        } else if (smileProbability <= SMILE_OFF_THRESHOLD && faceSmileActive) {
            faceSmileActive = false
            val neutral = expressions.firstOrNull {
                val n = normalize(it.name)
                n.contains("neutro") || n.contains("neutral")
            }
            if (neutral != null) selectExpression(neutral)
        }
    }

    private fun stopCameraTracking() {
        // Invalidate any pending CameraX provider callback before releasing resources.
        cameraStartGeneration++
        // CameraX may already be closing after permission revocation or lifecycle teardown.
        // A cleanup exception must not prevent the rest of LIVE from resetting its state.
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        cameraEnabled = false
        cameraStartInFlight = false
        cameraEyesClosed = false
        cameraMouthOpen = false
        faceSmileActive = false
        closedEyeSamples = 0
        openEyeSamples = 0
        openMouthSamples = 0
        closedMouthSamples = 0
        lastFaceSeenAt = 0L
        renderFrame()
        if (menuVisible) updateMenuContent()
    }

    private fun selectExpression(expression: AvatarExpression) {
        AvatarStore.select(this, expression.name)
        selectedFrames = expression.frames
        lastRenderedKey = ""
        renderFrame()
        if (menuVisible) updateMenuContent()
    }

    private fun toggleMenu() {
        if (menuVisible) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (avatarView == null || windowManager == null) return
        if (menuView != null) {
            if (!menuVisible) {
                runCatching { windowManager?.addView(menuView, menuParams) }
                    .onFailure {
                        Toast.makeText(this, "No se pudo abrir el menú.", Toast.LENGTH_SHORT).show()
                        return
                    }
            }
            menuVisible = true
            repositionMenuNearAvatar()
            updateMenuContent()
            return
        }

        // The operator window is anchored to the avatar, but its content has separate zones:
        // radial actions on top and a scrollable tool tray below so neither covers the other.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        menuView = root

        val controls = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val controlsParams = FrameLayout.LayoutParams(-1, dp(108), Gravity.TOP)
        root.addView(controls, controlsParams)

        fun radialButton(glyph: String, desc: String, buttonGravity: Int, action: () -> Unit) {
            val button = actionText(glyph, desc, action).apply {
                textSize = 22f
                this.gravity = Gravity.CENTER
                background = oval(Color.argb(232, 24, 28, 40))
                setTextColor(Color.WHITE)
                elevation = dp(4).toFloat()
            }
            val lp = FrameLayout.LayoutParams(dp(42), dp(42), buttonGravity)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            controls.addView(button, lp)
        }
        radialButton("😊", "Expresiones", Gravity.TOP or Gravity.CENTER_HORIZONTAL) {
            menuPage = PAGE_EXPRESSIONS
            updateMenuContent()
        }
        radialButton("⚙", "Ajustes", Gravity.CENTER_VERTICAL or Gravity.START) {
            menuPage = PAGE_OPTIONS
            updateMenuContent()
        }
        radialButton("🎙", "Voz", Gravity.CENTER_VERTICAL or Gravity.END) {
            menuPage = PAGE_VOICE
            updateMenuContent()
            startVoiceRecognition()
        }
        radialButton("🔒", "Bloquear o desbloquear avatar", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) {
            locked = !locked
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOCKED, locked).apply()
            applyAvatarAppearance()
            Toast.makeText(this, if (locked) "Avatar bloqueado" else "Avatar desbloqueado", Toast.LENGTH_SHORT).show()
            updateMenuContent()
        }
        radialButton("✕", "Cerrar menú", Gravity.TOP or Gravity.END) { hideMenu() }

        val panel = FrameLayout(this).apply {
            background = rounded(Color.argb(238, 20, 23, 32), dp(16))
            elevation = dp(8).toFloat()
        }
        val panelScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val panelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        menuContent = panelContent
        panelScroll.addView(panelContent, FrameLayout.LayoutParams(-1, -2))
        panel.addView(panelScroll, FrameLayout.LayoutParams(-1, -1))
        val panelParams = FrameLayout.LayoutParams(-1, dp(178), Gravity.BOTTOM)
        panelParams.setMargins(dp(4), 0, dp(4), dp(4))
        root.addView(panel, panelParams)

        val params = WindowManager.LayoutParams(
            dp(236), dp(292),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Dynamic VT-2D Private Controls"
        }
        menuParams = params
        repositionMenuParamsOnly(params)
        menuVisible = true
        runCatching { windowManager?.addView(root, params) }.onFailure {
            menuVisible = false
            menuView = null
            menuContent = null
            menuParams = null
            Toast.makeText(this, "No se pudo abrir el menú. Revisa el permiso de superposición.", Toast.LENGTH_LONG).show()
            return
        }
        updateMenuContent()
    }

    private fun repositionMenuParamsOnly(params: WindowManager.LayoutParams) {
        val avatar = avatarParams
        val size = displaySize()
        val width = params.width
        val height = params.height
        val maxX = (size.x - width).coerceAtLeast(0)
        val maxY = (size.y - height).coerceAtLeast(0)
        val centerX = (avatar?.x ?: dp(24)) + (avatar?.width ?: avatarSizePx) / 2
        val avatarY = avatar?.y ?: dp(140)
        val avatarH = avatar?.height ?: avatarSizePx
        params.x = (centerX - width / 2).coerceIn(0, maxX)
        params.y = if (avatarY + avatarH + dp(8) + height <= size.y) {
            (avatarY + avatarH + dp(8)).coerceIn(0, maxY)
        } else {
            (avatarY - height - dp(8)).coerceIn(0, maxY)
        }
    }

    private fun hideMenu() {
        if (menuVisible) {
            runCatching { menuView?.let { windowManager?.removeView(it) } }
        }
        menuVisible = false
        menuContent?.visibility = View.GONE
    }

    private fun updateMenuContent() {
        val content = menuContent ?: return
        content.removeAllViews()
        content.visibility = View.VISIBLE
        when (menuPage) {
            PAGE_EXPRESSIONS -> buildExpressionPage(content)
            PAGE_OPTIONS -> buildOptionsPage(content)
            else -> buildVoicePage(content)
        }
        // Keep the radial buttons visible; the selected tool appears in a compact bottom tray.
    }

    private fun buildExpressionPage(target: LinearLayout) {
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        modeRow.addView(actionText(if (circularMode) "☷ Lista" else "☷ Lista ✓", "Vista de lista") {
            circularMode = false
            updateMenuContent()
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        modeRow.addView(actionText(if (circularMode) "◉ Circular ✓" else "◉ Circular", "Vista circular desplazable") {
            circularMode = true
            updateMenuContent()
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        target.addView(modeRow)

        val selectedName = AvatarStore.selected(this)
        val expressions = AvatarStore.load(this)
        if (expressions.isEmpty()) {
            target.addView(label("Aún no hay expresiones. Vuelve al estudio e importa cuatro PNG por expresión.", 12f, MUTED))
            return
        }

        if (circularMode) {
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), dp(2), dp(8))
            }
            expressions.forEach { expression ->
                val circle = TextView(this).apply {
                    text = emojiFor(expression.name) + "\n" + expression.name.take(9)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = oval(if (expression.name == selectedName) COLOR_ACCENT else COLOR_CARD)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    contentDescription = "Usar expresión ${expression.name}"
                    setOnClickListener { selectExpression(expression) }
                }
                val params = LinearLayout.LayoutParams(dp(72), dp(72))
                params.marginEnd = dp(8)
                row.addView(circle, params)
            }
            scroll.addView(row)
            target.addView(scroll, LinearLayout.LayoutParams(-1, dp(106)))
        } else {
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            expressions.forEach { expression ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (expression.name == selectedName) COLOR_SELECTED else COLOR_CARD, dp(12))
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                }
                val text = label("${emojiFor(expression.name)}  ${expression.name}", 13f, Color.WHITE)
                row.addView(text, LinearLayout.LayoutParams(0, dp(44), 1f))
                row.setOnClickListener { selectExpression(expression) }
                val lp = LinearLayout.LayoutParams(-1, dp(48))
                lp.bottomMargin = dp(6)
                list.addView(row, lp)
            }
            scroll.addView(list)
            // The panel content is measured inside a ScrollView with wrap-content height;
            // a zero-height weighted child can collapse, hiding every list expression.
            target.addView(scroll, LinearLayout.LayoutParams(-1, dp(92)))
        }
        target.addView(label("Desliza para ver todas tus expresiones. Selecciona una para cambiar el avatar.", 11f, MUTED))
    }

    private fun buildOptionsPage(target: LinearLayout) {
        target.addView(label("AJUSTES RÁPIDOS", 12f, COLOR_ACCENT))
        target.addView(actionButton(if (locked) "🔓 Desbloquear avatar" else "🔒 Bloquear avatar") {
            locked = !locked
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOCKED, locked).apply()
            applyAvatarAppearance()
            updateMenuContent()
        })
        target.addView(actionButton(if (cameraEnabled || cameraStartInFlight) "📷 Desactivar seguimiento facial" else "📷 Activar seguimiento facial") {
            if (cameraEnabled || cameraStartInFlight) stopCameraTracking() else startCameraTracking()
            updateMenuContent()
        })
        target.addView(actionButton("🎙 Reconocer comando de voz") {
            menuPage = PAGE_VOICE
            updateMenuContent()
            startVoiceRecognition()
        })
        target.addView(actionButton("＋ Aumentar avatar") { resizeAvatar(1.15f) })
        target.addView(actionButton("－ Reducir avatar") { resizeAvatar(0.87f) })
        target.addView(actionButton("◐ Restaurar opacidad") {
            avatarOpacity = 1f
            AvatarStore.setOpacity(this, avatarOpacity)
            applyAvatarAppearance()
            updateMenuContent()
        })
        target.addView(actionButton("💥 Efecto golpe + pajaritos") { playHitEffect() })
        target.addView(actionButton("⏹ Detener LIVE") { stopSelf() })
        target.addView(label(
            "Cámara: ${if (cameraEnabled) "activa" else if (cameraStartInFlight) "iniciando" else "apagada"}\n" +
                "Bloqueo: ${if (locked) "activado" else "desactivado"}\n" +
                "El bloqueo solo evita mover el avatar; no lo vuelve transparente.",
            11f,
            MUTED
        ))
    }

    private fun buildVoicePage(target: LinearLayout) {
        target.addView(label("CONTROL POR VOZ", 12f, COLOR_ACCENT))
        target.addView(label("Pulsa escuchar y di el nombre de una expresión, «abrir menú», «cerrar menú», «bloquear», «desbloquear», «activar cámara» o «detener LIVE».", 13f, Color.WHITE))
        target.addView(actionButton("🎙 Escuchar ahora") { startVoiceRecognition() })
        target.addView(actionButton("↩ Volver a expresiones") {
            menuPage = PAGE_EXPRESSIONS
            updateMenuContent()
        })
        target.addView(label("Idioma de reconocimiento: español (Colombia).", 11f, MUTED))
    }

    private fun resizeAvatar(factor: Float) {
        val params = avatarParams ?: return
        val next = (params.width * factor).toInt().coerceIn(dp(80), dp(360))
        params.width = next
        params.height = next
        avatarSizePx = next
        AvatarStore.setSize(this, next / resources.displayMetrics.density)
        runCatching { windowManager?.updateViewLayout(avatarView, params) }
        if (menuVisible) repositionMenuNearAvatar()
    }

    private fun startVoiceRecognition(showListeningToast: Boolean = true) {
        voiceListeningRequested = true
        handler.removeCallbacks(voiceRestartRunnable)
        voiceRestartScheduled = false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Concede el permiso de micrófono desde el estudio.", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "El teléfono no tiene un servicio de reconocimiento de voz disponible.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            runCatching { speechRecognizer?.destroy() }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    if (speechRecognizer !== recognizer) return
                    voiceMouthOpen = false
                    voiceLevelActive = false
                    setSpeakingMotion(false)
                    renderFrame()
                }
                override fun onBeginningOfSpeech() {
                    if (speechRecognizer !== recognizer) return
                    voiceLevelActive = true
                    setSpeakingMotion(true)
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (speechRecognizer !== recognizer || !voiceLevelActive) return
                    // SpeechRecognizer reports RMS in dB with device-dependent ranges.
                    // Use a lower threshold plus separate close hysteresis so quiet speech
                    // still animates the mouth without rapid open/close flicker.
                    val speaking = if (voiceMouthOpen) rmsdB >= VOICE_MOUTH_CLOSE_RMS
                        else rmsdB >= VOICE_MOUTH_OPEN_RMS
                    if (speaking != voiceMouthOpen) {
                        voiceMouthOpen = speaking
                        setSpeakingMotion(speaking)
                        renderFrame()
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (speechRecognizer !== recognizer) return
                    voiceLevelActive = false
                    voiceMouthOpen = false
                    setSpeakingMotion(false)
                    renderFrame()
                }
                override fun onError(error: Int) {
                    if (speechRecognizer !== recognizer) return
                    voiceMouthOpen = false
                    setSpeakingMotion(false)
                    renderFrame()
                    runCatching { speechRecognizer?.destroy() }
                    speechRecognizer = null
                    scheduleVoiceRecognitionRestart()
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@AvatarOverlayService, "No se entendió el comando. Prueba otra vez.", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    if (speechRecognizer !== recognizer) return
                    voiceMouthOpen = false
                    setSpeakingMotion(false)
                    renderFrame()
                    voiceLevelActive = false
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val phrase = phrases.firstOrNull { isKnownVoiceCommand(it) }
                        ?: phrases.firstOrNull().orEmpty()
                    if (phrase.isNotBlank()) processVoiceCommand(phrase)
                    runCatching { speechRecognizer?.destroy() }
                    speechRecognizer = null
                    scheduleVoiceRecognitionRestart()
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    // Act only on final results to avoid executing the same command repeatedly
                    // as Android refines partial transcripts.
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-CO")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            recognizer.startListening(intent)
            if (showListeningToast) Toast.makeText(this, "Escuchando voz continuamente; di un comando cuando quieras.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            voiceMouthOpen = false
            renderFrame()
            Toast.makeText(this, "No se pudo iniciar el reconocimiento de voz.", Toast.LENGTH_LONG).show()
            scheduleVoiceRecognitionRestart()
        }
    }

    private fun isKnownVoiceCommand(raw: String): Boolean {
        val command = normalize(raw)
        if (command.contains("cerrar menu") || command.contains("ocultar menu") ||
            command.contains("abrir menu") || command == "menu" || command.contains("mostrar menu") ||
            command.contains("desbloquear") || command.contains("bloquear") ||
            command.contains("activar camara") || command.contains("iniciar camara") ||
            command.contains("seguimiento facial") || command.contains("detener camara") ||
            command.contains("apagar camara") || command.contains("detener live") ||
            command.contains("parar live") || command.contains("cerrar live")) return true
        return AvatarStore.load(this).any { normalize(it.name).let { n -> n.isNotBlank() && command.contains(n) } }
    }

    private fun processVoiceCommand(raw: String) {
        val command = normalize(raw)
        when {
            command.contains("cerrar menu") || command.contains("ocultar menu") -> {
                hideMenu()
                return
            }
            command.contains("abrir menu") || command == "menu" || command.contains("mostrar menu") -> {
                if (!menuVisible) showMenu()
                return
            }
            command.contains("desbloquear") -> {
                locked = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOCKED, false).apply()
                applyAvatarAppearance()
                if (!menuVisible) showMenu()
                if (menuVisible) updateMenuContent()
                return
            }
            command.contains("bloquear") -> {
                locked = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOCKED, true).apply()
                applyAvatarAppearance()
                if (menuVisible) updateMenuContent()
                return
            }
            command.contains("activar camara") || command.contains("iniciar camara") || command.contains("seguimiento facial") -> {
                startCameraTracking()
                if (menuVisible) updateMenuContent()
                return
            }
            command.contains("detener camara") || command.contains("apagar camara") -> {
                stopCameraTracking()
                if (menuVisible) updateMenuContent()
                return
            }
            command.contains("detener live") || command.contains("parar live") ||
                command.contains("cerrar live") -> {
                stopSelf()
                return
            }
        }

        val expression = AvatarStore.load(this).firstOrNull { normalize(it.name).let { n -> n.isNotBlank() && command.contains(n) } }
        if (expression != null) {
            selectExpression(expression)
        } else {
            Toast.makeText(this, "No reconocí una orden ni una expresión: «$raw».", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionText(text: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
            setOnClickListener { action() }
        }

    private fun actionButton(text: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(COLOR_CARD, dp(12))
            setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(-1, dp(42))
            lp.topMargin = dp(6)
            layoutParams = lp
        }

    private fun label(text: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }

    private fun emojiFor(name: String): String {
        val n = normalize(name)
        return when {
            n.contains("feliz") || n.contains("happy") || n.contains("alegre") -> "😊"
            n.contains("enoj") || n.contains("angry") -> "😠"
            n.contains("triste") || n.contains("sad") -> "😢"
            n.contains("confund") || n.contains("confus") -> "🤔"
            n.contains("sorpr") || n.contains("surprise") -> "😮"
            n.contains("neutro") || n.contains("neutral") -> "😐"
            else -> "🎭"
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        avatarView?.animate()?.cancel()
        effectView?.animate()?.cancel()
        if (configurationReceiverRegistered) {
            runCatching { unregisterReceiver(configurationReceiver) }
            configurationReceiverRegistered = false
        }
        voiceListeningRequested = false
        handler.removeCallbacks(voiceRestartRunnable)
        voiceRestartScheduled = false
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        // Invalidate a pending CameraX provider callback before tearing down this LifecycleService.
        // Otherwise a late callback could attempt to bind the camera after LIVE has been destroyed.
        cameraStartGeneration++
        cameraStartInFlight = false
        cameraEnabled = false
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        faceDetector?.close()
        faceDetector = null
        cameraExecutor.shutdown()
        avatarView?.let { runCatching { windowManager?.removeView(it) } }
        menuView?.let { runCatching { windowManager?.removeView(it) } }
        avatarView = null
        avatarImage = null
        fallbackView = null
        menuView = null
        menuContent = null
        avatarParams = null
        menuParams = null
        windowManager = null
        super.onDestroy()
    }


    companion object {
        const val ACTION_STOP = "com.juangema.dynamicvt2d.STOP_OVERLAY"
        private const val PREFS = "dynamic_vt2d"
        private const val KEY_LOCKED = "avatar_locked"
        private const val NOTIFICATION_ID = 2207
        private const val DOUBLE_TAP_MS = 300L
        private const val EYE_CLOSED_THRESHOLD = 0.38f
        private const val EYE_CLOSE_CONFIRM_FRAMES = 2
        private const val EYE_OPEN_CONFIRM_FRAMES = 2
        private const val MOUTH_OPEN_THRESHOLD = 0.022
        private const val MOUTH_OPEN_CONFIRM_FRAMES = 2
        private const val MOUTH_CLOSE_CONFIRM_FRAMES = 2
        private const val FACE_LOST_RESET_MS = 650L
        private const val MAX_CACHED_BITMAPS = 12
        private const val VOICE_MOUTH_OPEN_RMS = 1.8f
        private const val VOICE_MOUTH_CLOSE_RMS = 1.2f
        private const val SMILE_ON_THRESHOLD = 0.82f
        private const val SMILE_OFF_THRESHOLD = 0.42f
        private const val PAGE_EXPRESSIONS = 0
        private const val PAGE_OPTIONS = 1
        private const val PAGE_VOICE = 2
        private val COLOR_PANEL = Color.rgb(17, 22, 36)
        private val COLOR_CARD = Color.rgb(35, 43, 64)
        private val COLOR_SELECTED = Color.rgb(44, 60, 101)
        private val COLOR_ACCENT = Color.rgb(112, 145, 255)
        private val MUTED = Color.rgb(187, 198, 219)
    }
}

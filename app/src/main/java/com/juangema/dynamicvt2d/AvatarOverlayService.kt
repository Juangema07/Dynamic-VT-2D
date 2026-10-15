package com.juangema.dynamicvt2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shows the avatar in a normal overlay window and the private operator panel
 * in a separate FLAG_SECURE window. Screen capture can include the avatar
 * without including the controls.
 */
class AvatarOverlayService : LifecycleService() {
    private var windowManager: WindowManager? = null

    private var avatarView: FrameLayout? = null
    private var avatarImage: ImageView? = null
    private var fallbackView: TextView? = null
    private var avatarParams: WindowManager.LayoutParams? = null

    private var menuView: LinearLayout? = null
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
    private var cameraEyesClosed = false
    private var cameraMouthOpen = false
    private var faceSmileActive = false

    private var selectedFrames: List<String> = emptyList()
    private var avatarOpacity = 1f
    private var locked = false
    private var blinkClosed = false
    private var voiceMouthOpen = false
    private var lastRenderedKey = ""
    private val bitmapCache = mutableMapOf<String, Bitmap?>()

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var movedDuringTouch = false
    private var lastTapTime = 0L
    private var avatarSizePx = 0

    private var speechRecognizer: SpeechRecognizer? = null

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
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
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
        if (menuVisible) showMenu()
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
        val alpha = if (locked) avatarOpacity * LOCKED_ALPHA else avatarOpacity
        avatarView?.alpha = alpha.coerceIn(0.1f, 1f)
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
            if (bitmapCache.containsKey(key)) bitmapCache[key]
            else runCatching {
                contentResolver.openInputStream(Uri.parse(key))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull().also { bitmapCache[key] = it }
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()
                val analysis = ImageAnalysis.Builder()
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
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face != null) {
                    val leftOpen = face.leftEyeOpenProbability ?: 1f
                    val rightOpen = face.rightEyeOpenProbability ?: 1f
                    val closed = leftOpen < EYE_CLOSED_THRESHOLD && rightOpen < EYE_CLOSED_THRESHOLD

                    val upperLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                    val lowerLip = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
                    val mouthGap = if (!upperLip.isNullOrEmpty() && !lowerLip.isNullOrEmpty()) {
                        val upperY = upperLip.map { it.y.toDouble() }.average()
                        val lowerY = lowerLip.map { it.y.toDouble() }.average()
                        (lowerY - upperY) / face.boundingBox.height().coerceAtLeast(1)
                    } else 0.0
                    val mouthOpen = mouthGap > MOUTH_OPEN_THRESHOLD

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
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraEnabled = false
        cameraStartInFlight = false
        cameraEyesClosed = false
        cameraMouthOpen = false
        faceSmileActive = false
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
                menuVisible = true
            }
            updateMenuContent()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = rounded(COLOR_PANEL, dp(18))
        }
        menuView = root

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "DYNAMIC  •  LIVE"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(actionText("✕", "Cerrar panel") { hideMenu() }, LinearLayout.LayoutParams(dp(34), dp(38)))
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabs.addView(actionText("Expresiones", "Mostrar expresiones") {
            menuPage = PAGE_EXPRESSIONS
            updateMenuContent()
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        tabs.addView(actionText("Opciones", "Mostrar opciones") {
            menuPage = PAGE_OPTIONS
            updateMenuContent()
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        tabs.addView(actionText("Voz 🎙", "Escuchar comando de voz") {
            menuPage = PAGE_VOICE
            updateMenuContent()
            startVoiceRecognition()
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(tabs)

        val divider = View(this).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuContent = content
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val metrics = resources.displayMetrics
        val menuWidth = dp(292)
        val menuHeight = (metrics.heightPixels - dp(70)).coerceAtLeast(dp(300)).coerceAtMost(dp(480))
        val avatar = avatarParams
        val xRight = (avatar?.x ?: dp(24)) + (avatar?.width ?: avatarSizePx) + dp(10)
        val xLeft = (avatar?.x ?: dp(24)) - menuWidth - dp(10)
        val x = if (xRight + menuWidth < metrics.widthPixels) xRight else xLeft.coerceAtLeast(dp(4))
        val y = (avatar?.y ?: dp(140)).coerceIn(dp(4), (metrics.heightPixels - menuHeight - dp(8)).coerceAtLeast(dp(4)))
        val params = WindowManager.LayoutParams(
            menuWidth,
            menuHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "Dynamic VT-2D Private Controls"
        }
        menuParams = params
        menuVisible = true
        runCatching {
            windowManager?.addView(root, params)
            updateMenuContent()
        }.onFailure {
            menuVisible = false
            menuView = null
            menuContent = null
            Toast.makeText(this, "No se pudo abrir el panel privado. Revisa el permiso de superposición.", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideMenu() {
        if (menuVisible) {
            runCatching { menuView?.let { windowManager?.removeView(it) } }
        }
        menuVisible = false
    }

    private fun updateMenuContent() {
        val content = menuContent ?: return
        content.removeAllViews()
        when (menuPage) {
            PAGE_EXPRESSIONS -> buildExpressionPage(content)
            PAGE_OPTIONS -> buildOptionsPage(content)
            else -> buildVoicePage(content)
        }
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
            target.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
        target.addView(actionButton("⏹ Detener LIVE") { stopSelf() })
        target.addView(label(
            "Cámara: ${if (cameraEnabled) "activa" else if (cameraStartInFlight) "iniciando" else "apagada"}\n" +
                "Bloqueo: ${if (locked) "activado" else "desactivado"}\n" +
                "El panel utiliza una ventana protegida para no salir en la captura de pantalla.",
            11f,
            MUTED
        ))
    }

    private fun buildVoicePage(target: LinearLayout) {
        target.addView(label("CONTROL POR VOZ", 12f, COLOR_ACCENT))
        target.addView(label("Pulsa escuchar y di el nombre de una expresión, «abrir menú», «cerrar menú», «bloquear», «desbloquear» o «activar cámara».", 13f, Color.WHITE))
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
        if (menuVisible) showMenu()
    }

    private fun startVoiceRecognition() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Concede el permiso de micrófono desde el estudio.", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "El teléfono no tiene un servicio de reconocimiento de voz disponible.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    voiceMouthOpen = true
                    renderFrame()
                }
                override fun onBeginningOfSpeech() {
                    voiceMouthOpen = true
                    renderFrame()
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    voiceMouthOpen = false
                    renderFrame()
                }
                override fun onError(error: Int) {
                    voiceMouthOpen = false
                    renderFrame()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@AvatarOverlayService, "No se entendió el comando. Prueba otra vez.", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    voiceMouthOpen = false
                    renderFrame()
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val phrase = phrases?.firstOrNull().orEmpty()
                    if (phrase.isNotBlank()) processVoiceCommand(phrase)
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-CO")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)
            Toast.makeText(this, "Escuchando comando de voz…", Toast.LENGTH_SHORT).show()
        }.onFailure {
            speechRecognizer?.destroy()
            speechRecognizer = null
            voiceMouthOpen = false
            renderFrame()
            Toast.makeText(this, "No se pudo iniciar el reconocimiento de voz.", Toast.LENGTH_LONG).show()
        }
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
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
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

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_STOP = "com.juangema.dynamicvt2d.STOP_OVERLAY"
        private const val PREFS = "dynamic_vt2d"
        private const val KEY_LOCKED = "avatar_locked"
        private const val NOTIFICATION_ID = 2207
        private const val DOUBLE_TAP_MS = 300L
        private const val LOCKED_ALPHA = 0.34f
        private const val EYE_CLOSED_THRESHOLD = 0.42f
        private const val MOUTH_OPEN_THRESHOLD = 0.018
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

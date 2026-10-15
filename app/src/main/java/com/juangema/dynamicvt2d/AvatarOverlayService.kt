package com.juangema.dynamicvt2d

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

class AvatarOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var imageView: ImageView? = null
    private var fallbackView: TextView? = null
    private var menuView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var recordingMode = false
    @Volatile private var mouthOpen = false
    private var blinkClosed = false
    private var selectedFrames: List<String> = emptyList()
    private var expressions: List<AvatarExpression> = emptyList()
    private var selectedExpressionIndex = 0
    private var avatarSizePx = 0
    private var avatarOpacity = 1f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var lastTapAt = 0L
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioThreadRunning = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (AvatarStore.blinkMode(this@AvatarOverlayService) == "AUTO") {
                blinkClosed = true
                renderFrame()
                handler.postDelayed({
                    blinkClosed = false
                    renderFrame()
                    handler.postDelayed(this, Random.nextLong(1800L, 4200L))
                }, 130L)
            } else {
                blinkClosed = false
                handler.postDelayed(this, 700L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        configureAndShow()
        return START_STICKY
    }

    private fun configureAndShow() {
        expressions = AvatarStore.load(this)
        val selected = AvatarStore.selectedExpression(this)
        selectedExpressionIndex = expressions.indexOfFirst { it.name == selected?.name }.coerceAtLeast(0)
        selectedFrames = selected?.frames ?: emptyList()
        avatarSizePx = (AvatarStore.size(this) * resources.displayMetrics.density).toInt()
        avatarOpacity = AvatarStore.opacity(this).coerceIn(0.2f, 1f)

        if (rootView == null) {
            val root = FrameLayout(this)
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Avatar flotante Dynamic VT-2D. Toque para controles; pulsación larga para modo grabación."
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
            rootView = root
            imageView = image
            fallbackView = fallback
            root.setOnTouchListener { _, event -> handleTouch(event) }
            val params = WindowManager.LayoutParams(
                avatarSizePx, avatarSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_x", 40)
                y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_y", 180)
            }
            layoutParams = params
            windowManager?.addView(root, params)
        } else {
            layoutParams?.let {
                it.width = avatarSizePx
                it.height = avatarSizePx
                runCatching { windowManager?.updateViewLayout(rootView, it) }
            }
        }
        rootView?.alpha = avatarOpacity
        blinkClosed = false
        mouthOpen = false
        renderFrame()
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, Random.nextLong(1600L, 3000L))
        startVoiceDetectionIfNeeded()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                downAt = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (abs(dx) + abs(dy) > 12) {
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(rootView, params) }
                    positionMenu()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
                val moved = abs(event.rawX - downX) + abs(event.rawY - downY) > 18
                val held = event.eventTime - downAt >= 650L
                if (!moved && held) {
                    recordingMode = !recordingMode
                    if (recordingMode) hideMenu() else showMenu()
                    Toast.makeText(this, if (recordingMode) "Modo limpio activado" else "Modo de controles activado", Toast.LENGTH_SHORT).show()
                } else if (!moved) {
                    if (recordingMode) {
                        cycleExpression()
                    } else if (event.eventTime - lastTapAt < 350L) {
                        cycleExpression()
                        lastTapAt = 0L
                    } else {
                        lastTapAt = event.eventTime
                        if (menuView == null) showMenu() else hideMenu()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun cycleExpression() {
        expressions = AvatarStore.load(this)
        if (expressions.isEmpty()) {
            Toast.makeText(this, "Importa expresiones desde Dynamic VT-2D", Toast.LENGTH_SHORT).show()
            return
        }
        selectedExpressionIndex = (selectedExpressionIndex + 1) % expressions.size
        val expression = expressions[selectedExpressionIndex]
        AvatarStore.select(this, expression.name)
        selectedFrames = expression.frames
        blinkClosed = false
        renderFrame()
    }

    private fun showMenu() {
        if (recordingMode || menuView != null || windowManager == null) return
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 24, 29, 44))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(104, 126, 190))
            }
        }
        addMenuAction(menu, "Expresión siguiente") { cycleExpression() }
        addMenuAction(menu, "Parpadeo: ${if (AvatarStore.blinkMode(this) == "AUTO") "auto" else if (AvatarStore.blinkMode(this) == "CAMERA") "cámara" else "apagado"}") {
            val next = when (AvatarStore.blinkMode(this)) { "AUTO" -> "CAMERA"; "CAMERA" -> "OFF"; else -> "AUTO" }
            AvatarStore.setBlinkMode(this, next)
            if (next == "CAMERA") Toast.makeText(this, "Modo cámara seleccionado; seguimiento ocular no disponible aún.", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, "Parpadeo: ${next.lowercase()}", Toast.LENGTH_SHORT).show()
            hideMenu()
        }
        addMenuAction(menu, "Boca: ${if (AvatarStore.mouthMode(this) == "VOICE") "voz" else "cámara"}") {
            val next = if (AvatarStore.mouthMode(this) == "VOICE") "CAMERA" else "VOICE"
            AvatarStore.setMouthMode(this, next)
            if (next == "CAMERA") Toast.makeText(this, "Modo cámara seleccionado; seguimiento de boca no disponible aún.", Toast.LENGTH_LONG).show()
            else {
                startVoiceDetectionIfNeeded()
                Toast.makeText(this, "Boca por sonido del micrófono", Toast.LENGTH_SHORT).show()
            }
            hideMenu()
        }
        addMenuAction(menu, "Tamaño +") { resizeAvatar(1.12f) }
        addMenuAction(menu, "Tamaño −") { resizeAvatar(0.89f) }
        addMenuAction(menu, "Opacidad") {
            avatarOpacity = if (avatarOpacity > 0.7f) 0.55f else 1f
            AvatarStore.setOpacity(this, avatarOpacity)
            rootView?.alpha = avatarOpacity
            hideMenu()
        }
        addMenuAction(menu, "Modo grabación: ${if (recordingMode) "salir" else "activar"}") {
            recordingMode = !recordingMode
            if (recordingMode) hideMenu() else showMenu()
        }
        addMenuAction(menu, "Cerrar controles") { hideMenu() }
        menuView = menu
        menuParams = WindowManager.LayoutParams(
            dp(205), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionMenu()
        runCatching { windowManager?.addView(menu, menuParams) }.onFailure { menuView = null; menuParams = null }
    }

    private fun addMenuAction(menu: LinearLayout, label: String, action: () -> Unit) {
        val item = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { action() }
        }
        menu.addView(item, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun positionMenu() {
        val p = menuParams ?: return
        val avatar = layoutParams ?: return
        p.x = (avatar.x + avatar.width).coerceAtMost((resources.displayMetrics.widthPixels - dp(210)).coerceAtLeast(0))
        p.y = avatar.y.coerceAtMost((resources.displayMetrics.heightPixels - dp(360)).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(menuView, p) }
    }

    private fun hideMenu() {
        menuView?.let { runCatching { windowManager?.removeView(it) } }
        menuView = null
        menuParams = null
    }

    private fun resizeAvatar(factor: Float) {
        val p = layoutParams ?: return
        val density = resources.displayMetrics.density
        val newDp = (p.width / density * factor).coerceIn(90f, 320f)
        avatarSizePx = (newDp * density).toInt()
        AvatarStore.setSize(this, newDp)
        p.width = avatarSizePx
        p.height = avatarSizePx
        runCatching { windowManager?.updateViewLayout(rootView, p) }
        hideMenu()
    }

    private fun startVoiceDetectionIfNeeded() {
        if (AvatarStore.mouthMode(this) != "VOICE" ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            audioThreadRunning) return
        audioThreadRunning = true
        thread(name = "DynamicVT2D-Audio", isDaemon = true) {
            val sampleRate = 16000
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) { audioThreadRunning = false; return@thread }
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 2048))
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                audioThreadRunning = false
                return@thread
            }
            audioRecord = recorder
            val buffer = ShortArray(maxOf(1024, minBuffer / 2))
            try {
                recorder.startRecording()
                while (audioThreadRunning && !destroyed && AvatarStore.mouthMode(this) == "VOICE") {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        var sum = 0.0
                        for (i in 0 until count) sum += buffer[i].toDouble() * buffer[i].toDouble()
                        val rms = kotlin.math.sqrt(sum / count)
                        val open = rms > 850.0
                        if (open != mouthOpen) {
                            mouthOpen = open
                            handler.post { renderFrame() }
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                audioRecord = null
                audioThreadRunning = false
                mouthOpen = false
                handler.post { renderFrame() }
            }
        }
    }

    private fun renderFrame() {
        val frameIndex = (if (blinkClosed && AvatarStore.blinkMode(this) != "OFF") 2 else 0) + if (mouthOpen && AvatarStore.mouthMode(this) == "VOICE") 1 else 0
        val uriText = selectedFrames.getOrNull(frameIndex)
        val bitmap = uriText?.let { text ->
            runCatching { contentResolver.openInputStream(Uri.parse(text))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (bitmap != null) {
            imageView?.setImageBitmap(bitmap)
            imageView?.visibility = View.VISIBLE
            fallbackView?.visibility = View.GONE
        } else {
            imageView?.setImageDrawable(null)
            imageView?.visibility = View.GONE
            fallbackView?.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val p = layoutParams ?: return@post
            p.x = p.x.coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
            runCatching { windowManager?.updateViewLayout(rootView, p) }
            positionMenu()
        }
    }

    override fun onDestroy() {
        destroyed = true
        audioThreadRunning = false
        runCatching { audioRecord?.stop() }
        handler.removeCallbacksAndMessages(null)
        hideMenu()
        rootView?.let { view -> runCatching { windowManager?.removeView(view) } }
        rootView = null
        imageView = null
        fallbackView = null
        layoutParams = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.juangema.dynamicvt2d.STOP_OVERLAY"
        private const val PREFS = "dynamic_vt2d"
    }
}

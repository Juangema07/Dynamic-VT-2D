package com.juangema.dynamicvt2d

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import kotlin.random.Random

class AvatarOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var imageView: ImageView? = null
    private var fallbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var blinkClosed = false
    private var selectedFrames: List<String> = emptyList()
    private var mouthOpen = false
    private var avatarSizePx = 0
    private var avatarOpacity = 1f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkClosed = true
            renderFrame()
            handler.postDelayed({
                blinkClosed = false
                renderFrame()
                handler.postDelayed(this, Random.nextLong(1800L, 4200L))
            }, 130L)
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
        return START_NOT_STICKY
    }

    private fun configureAndShow() {
        val expression = AvatarStore.selectedExpression(this)
        selectedFrames = expression?.frames ?: emptyList()
        avatarSizePx = (AvatarStore.size(this) * resources.displayMetrics.density).toInt()
        avatarOpacity = AvatarStore.opacity(this).coerceIn(0.2f, 1f)

        if (rootView == null) {
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
        handler.postDelayed(blinkRunnable, Random.nextLong(2000L, 5000L))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = startX + (event.rawX - downX).toInt()
                params.y = startY + (event.rawY - downY).toInt()
                runCatching { windowManager?.updateViewLayout(rootView, params) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
                return true
            }
        }
        return false
    }

    private fun renderFrame() {
        val frameIndex = (if (blinkClosed) 2 else 0) + if (mouthOpen) 1 else 0
        val uriText = selectedFrames.getOrNull(frameIndex)
        val bitmap = uriText?.let { text ->
            runCatching {
                contentResolver.openInputStream(Uri.parse(text))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

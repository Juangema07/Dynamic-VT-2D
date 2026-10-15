package com.juangema.dynamicvt2d

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Primera base de la ventana flotante. Se reemplazará el emoji de prueba por
 * los fotogramas PNG transparentes del avatar y controles de tamaño/opacidad.
 */
class AvatarOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var avatarView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "🙂"
            textSize = 72f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Avatar Dynamic VT-2D"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }
        avatarView = view
        windowManager?.addView(view, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onDestroy() {
        avatarView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        avatarView = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

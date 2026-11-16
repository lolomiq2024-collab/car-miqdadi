package com.islamic.companion

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** شريط صغير عائم فوق التطبيقات: عنوان التشغيل + الصلاة القادمة + أزرار تحكم. قابل للسحب. */
class FloatingService : Service() {
    companion object { @Volatile var instance: FloatingService? = null }

    private var bar: LinearLayout? = null
    private var tTitle: TextView? = null
    private var tSub: TextView? = null
    private var bPlay: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this, "float", "الشريط العائم")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "float") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("الشريط العائم يعمل").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(11, n)
        if (bar == null) build()
        instance = this
        return START_NOT_STICKY
    }

    fun setInfo(title: String, sub: String, playing: Boolean) {
        ui.post { tTitle?.text = title; tSub?.text = sub; bPlay?.text = if (playing) "⏸" else "▶" }
    }

    private fun ctl(cmd: String) {
        if (cmd == "open") {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } else {
            sendBroadcast(Intent(CTRL_ACTION).setPackage(packageName).putExtra("cmd", cmd))
        }
    }

    private fun build() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        fun btn(label: String, cmd: String) = TextView(this).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { ctl(cmd) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tTitle = TextView(this).apply { text = "لا يوجد تشغيل"; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1 }
        tSub = TextView(this).apply { text = ""; textSize = 12f; setTextColor(0xFFB9B0CC.toInt()); maxLines = 1 }
        col.addView(tTitle); col.addView(tSub)
        bPlay = btn("▶", "play")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minimumWidth = (340 * dp).toInt()
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xE62A2352.toInt()); cornerRadius = 28 * dp }
        }
        root.addView(col); root.addView(btn("⏮", "prev")); root.addView(bPlay); root.addView(btn("⏭", "next")); root.addView(btn("↗", "open"))

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = (16 * dp).toInt(); lp.y = (60 * dp).toInt()

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ix + (e.rawX - tx).toInt(); lp.y = iy + (e.rawY - ty).toInt()
                    try { wm.updateViewLayout(root, lp) } catch (x: Exception) { }
                }
            }
            true
        }
        try { wm.addView(root, lp); bar = root } catch (e: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        instance = null
        bar?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        bar = null
        super.onDestroy()
    }
}

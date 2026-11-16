package com.islamic.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** يشغّل الأذان في الخلفية، ويعرض نافذة فوق التطبيقات (إن سُمح بذلك) وإشعاراً بزر إيقاف. */
class AdhanService : Service() {
    private var mp: MediaPlayer? = null
    private var overlay: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val name = i?.getStringExtra("name") ?: "الصلاة"
        val fajr = i?.getBooleanExtra("fajr", false) ?: false
        startFg(name)
        // أوقف ما يُشغَّل في التطبيق
        sendBroadcast(Intent(CTRL_ACTION).setPackage(packageName).putExtra("cmd", "pause"))
        play(fajr)
        showOverlay(name)
        return START_NOT_STICKY
    }

    private fun startFg(name: String) {
        ensureChannel(this, "adhan", "الأذان")
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AdhanService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "adhan") else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("حان وقت صلاة $name")
            .setContentText("الله أكبر الله أكبر")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "إيقاف", stop).build())
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(10, n)
    }

    private fun play(fajr: Boolean) {
        try {
            val names = assets.list("audio/adhan") ?: emptyArray()
            val pick = listOf(if (fajr) "fajr.mp3" else "adhan.mp3", "adhan.mp3").firstOrNull { it in names }
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            if (pick != null) {
                assets.openFd("audio/adhan/$pick").use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            } else {
                // لا يوجد ملف أذان: نغمة تنبيه النظام
                p.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            }
            p.setOnCompletionListener { stopSelf() }
            p.prepare(); p.start()
            mp = p
        } catch (e: Exception) { stopSelf() }
    }

    private fun showOverlay(name: String) {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dp = resources.displayMetrics.density
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
                background = GradientDrawable().apply { setColor(0xF02A2352.toInt()); cornerRadius = 24 * dp }
            }
            box.addView(TextView(this).apply { text = "🕌 حان وقت صلاة $name"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
            box.addView(Button(this).apply { text = "إيقاف الأذان"; setOnClickListener { stopSelf() } })
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                       else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (40 * dp).toInt()
            wm.addView(box, lp)
            overlay = box
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { mp?.stop() } catch (e: Exception) { }
        mp?.release(); mp = null
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        overlay = null
        super.onDestroy()
    }
}

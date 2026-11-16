package com.islamic.companion

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.JavascriptInterface

const val CTRL_ACTION = "com.islamic.companion.CTRL"

fun ensureChannel(ctx: Context, id: String, name: String, importance: Int = NotificationManager.IMPORTANCE_LOW) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}

fun startFgService(ctx: Context, i: Intent) {
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
}

/** التنقل: يفتح تطبيق خرائط قوقل لبدء الملاحة، أو خرائط الويب الكاملة داخل التطبيق. */
class NavBridge(private val act: Activity) {
    @JavascriptInterface
    fun navigate(query: String) {
        act.runOnUiThread {
            val q = Uri.encode(query)
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))
                .setPackage("com.google.android.apps.maps")
            try {
                act.startActivity(nav)
            } catch (e: Exception) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=driving")))
                } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    fun openMaps(query: String) {
        act.runOnUiThread {
            val url = if (query.isBlank()) "https://www.google.com/maps"
                      else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query)
            act.startActivity(Intent(act, YouTubeActivity::class.java).putExtra("url", url))
        }
    }
}

/** الهاتف: يفتح تطبيق الاتصال في النظام (المربوط بالبلوتوث إن كان الجهاز يدعم ذلك). */
class PhoneBridge(private val act: Activity) {
    private fun go(i: Intent) {
        act.runOnUiThread { try { act.startActivity(i) } catch (e: ActivityNotFoundException) { } }
    }
    @JavascriptInterface fun dial(number: String) { go(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
    @JavascriptInterface fun bluetooth() { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    @JavascriptInterface fun callLog() { go(Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)) }
    @JavascriptInterface fun contacts() { go(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) }
}

/** الصلاة: جدولة الأذان في النظام ليعمل والتطبيق مغلق أو في الخلفية. */
class PrayerBridge(private val act: Activity) {
    @JavascriptInterface
    fun schedule(json: String) { PrayerScheduler.save(act, json); PrayerScheduler.apply(act) }

    @JavascriptInterface
    fun requestPermissions() {
        act.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    /** هل التطبيق مستثنى من توفير البطارية؟ */
    @JavascriptInterface
    fun batteryOk(): Boolean {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(act.packageName)
    }

    @JavascriptInterface
    fun requestBattery() {
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
            } catch (e: Exception) {
                try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    fun test() {
        act.runOnUiThread {
            startFgService(act, Intent(act, AdhanService::class.java)
                .putExtra("name", "اختبار").putExtra("fajr", false))
        }
    }
}

/** الشريط العائم فوق التطبيقات. */
class FloatBridge(private val act: Activity) {
    @JavascriptInterface fun canDraw(): Boolean = Settings.canDrawOverlays(act)

    @JavascriptInterface
    fun requestPermission() {
        act.runOnUiThread {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        }
    }

    @JavascriptInterface
    fun show() { act.runOnUiThread { startFgService(act, Intent(act, FloatingService::class.java)) } }

    @JavascriptInterface
    fun hide() { act.runOnUiThread { act.stopService(Intent(act, FloatingService::class.java)) } }

    @JavascriptInterface
    fun update(title: String, sub: String, playing: Boolean) { FloatingService.instance?.setInfo(title, sub, playing) }
}

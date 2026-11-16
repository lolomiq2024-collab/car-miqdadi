package com.islamic.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

object PrayerScheduler {
    private const val PREF = "prayer"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("json", json).apply()
    }

    private fun pending(ctx: Context, code: Int, name: String, fajr: Boolean): PendingIntent {
        val i = Intent(ctx, AdhanReceiver::class.java).putExtra("name", name).putExtra("fajr", fajr)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** يلغي التنبيهات القديمة ويجدول المحفوظة (المستقبلية فقط). */
    fun apply(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        (sp.getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() }.forEach {
            am.cancel(pending(ctx, it.toInt(), "", false))
        }

        val json = sp.getString("json", null) ?: return
        val root = try { JSONObject(json) } catch (e: Exception) { return }
        if (!root.optBoolean("adhan", true)) { sp.edit().putString("ids", "").apply(); return }

        val items = root.optJSONArray("items") ?: return
        val now = System.currentTimeMillis()
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ids = ArrayList<String>()
        for (n in 0 until items.length()) {
            val o = items.getJSONObject(n)
            val t = o.getLong("t")
            if (t <= now) continue
            val code = ((t / 60000L) % 2000000000L).toInt()
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show),
                pending(ctx, code, o.optString("n", "الصلاة"), o.optString("k") == "fajr"))
            ids.add(code.toString())
        }
        sp.edit().putString("ids", ids.joinToString(",")).apply()
    }
}

class AdhanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val s = Intent(ctx, AdhanService::class.java)
            .putExtra("name", i.getStringExtra("name"))
            .putExtra("fajr", i.getBooleanExtra("fajr", false))
        startFgService(ctx, s)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // بعد إعادة التشغيل أو تحديث التطبيق: أعد جدولة الأذان المحفوظ
        PrayerScheduler.apply(ctx)
    }
}

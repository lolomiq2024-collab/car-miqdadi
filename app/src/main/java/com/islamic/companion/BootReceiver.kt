package com.islamic.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // إعادة جدولة الأذان بعد إعادة تشغيل الجهاز
            PrayerScheduler.scheduleAll(context)
        }
    }
}

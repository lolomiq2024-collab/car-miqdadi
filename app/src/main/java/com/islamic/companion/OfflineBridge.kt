package com.islamic.companion

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** تنزيل سور القرآن إلى ذاكرة الجهاز للاستماع بدون إنترنت. يُعرَض للصفحة باسم AndroidDL. */
class OfflineBridge(
    private val act: Activity,
    private val web: WebView,
    private val dir: File
) {
    @Volatile private var running = false
    @Volatile private var cancelled = false

    private fun notify(key: String, n: Int, status: String) {
        act.runOnUiThread { web.evaluateJavascript("window.onDL&&onDL('$key',$n,'$status')", null) }
    }

    /** أرقام السور المنزَّلة لهذا القارئ، مثل [1,2,18] */
    @JavascriptInterface
    fun list(key: String): String {
        val nums = File(dir, key).listFiles()
            ?.filter { it.name.endsWith(".mp3") && it.length() > 0 }
            ?.mapNotNull { it.name.removeSuffix(".mp3").toIntOrNull() }
            ?: emptyList()
        return nums.joinToString(",", "[", "]")
    }

    @JavascriptInterface
    fun download(key: String, base: String) {
        if (running) return
        running = true; cancelled = false
        Thread {
            var status = "done"
            try {
                val d = File(dir, key); d.mkdirs()
                for (n in 1..114) {
                    if (cancelled) { status = "cancel"; break }
                    val name = String.format(Locale.US, "%03d.mp3", n)
                    val out = File(d, name)
                    if (out.exists() && out.length() > 0) { notify(key, n, "n"); continue }
                    val tmp = File(d, "$name.part")
                    val c = URL(base + name).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    try {
                        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    } finally { c.disconnect() }
                    if (!tmp.renameTo(out)) throw RuntimeException("rename")
                    notify(key, n, "n")
                }
            } catch (e: Exception) {
                status = "error"
            } finally {
                running = false
                notify(key, 0, status)
            }
        }.start()
    }

    @JavascriptInterface fun cancel() { cancelled = true }

    @JavascriptInterface
    fun remove(key: String) { if (!running) File(dir, key).deleteRecursively() }
}

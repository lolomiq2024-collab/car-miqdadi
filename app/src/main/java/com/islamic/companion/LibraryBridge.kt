package com.islamic.companion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * مكتبة الأغاني والدروس من الجهاز مباشرة: تُقرأ عند كل تحديث، فلا حاجة لإعادة بناء التطبيق.
 *   Music/IslamicCompanion/songs/<المطرب>/<الأغنية>.mp3
 *   Music/IslamicCompanion/lessons/<السلسلة - الشيخ>/<الحلقة>.mp3
 * وكذلك مجلد التطبيق الخاص: Android/data/com.islamic.companion/files/audio/ (بالبنية نفسها)
 */
class LibraryBridge(private val act: Activity, private val web: WebView) {

    companion object {
        private val EXT = setOf("mp3", "m4a", "ogg", "wav", "aac", "opus", "flac")

        /** (معرّف الجذر، المجلد). يُستخدم أيضاً لتقديم الملفات للصفحة عبر /local/<id>/... */
        fun roots(act: Activity): List<Pair<String, File>> {
            val list = ArrayList<Pair<String, File>>()
            list.add("m" to File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "IslamicCompanion"))
            act.getExternalFilesDir(null)?.let { list.add("a" to File(it, "audio")) }
            return list
        }

        private val natural = Comparator<File> { a, b ->
            val re = Regex("\\d+|\\D+")
            val pa = re.findAll(a.name).map { it.value }.toList()
            val pb = re.findAll(b.name).map { it.value }.toList()
            var i = 0
            var res = 0
            while (i < pa.size && i < pb.size && res == 0) {
                val x = pa[i]; val y = pb[i]
                res = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
                i++
            }
            if (res != 0) res else pa.size - pb.size
        }

        private fun audioFiles(d: File): List<File> =
            (d.listFiles() ?: emptyArray()).filter {
                it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in EXT
            }.sortedWith(natural)

        /** يحذف اسم المطرب من اسم الملف إن وُجد: «أغنية - مطرب» أو «مطرب - أغنية» */
        private fun clean(t: String, singer: String): String {
            var s = t
            for (sep in listOf(" - ", "-", "_")) {
                for (pat in listOf(sep + singer, singer + sep)) {
                    if (s.endsWith(pat) && s.length > pat.length) s = s.dropLast(pat.length)
                    else if (s.startsWith(pat) && s.length > pat.length) s = s.drop(pat.length)
                }
            }
            return s.trim().ifEmpty { t }
        }
    }

    private fun perm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    @JavascriptInterface
    fun hasPermission(): Boolean = act.checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun requestPermission() { act.runOnUiThread { act.requestPermissions(arrayOf(perm()), 3) } }

    /** مسار مجلد التطبيق الخاص (للعرض فقط) */
    @JavascriptInterface
    fun appDir(): String = act.getExternalFilesDir(null)?.let { File(it, "audio").absolutePath } ?: ""

    @JavascriptInterface
    fun scan(): String {
        roots(act).forEach { (id, r) -> if (id == "a") try { File(r, "songs").mkdirs(); File(r, "lessons").mkdirs() } catch (e: Exception) { } }
        val songs = JSONObject()
        val lessons = JSONArray()
        for ((id, root) in roots(act)) {
            try {
                (File(root, "songs").listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }.forEach { d ->
                    val files = audioFiles(d)
                    if (files.isNotEmpty()) {
                        val arr = songs.optJSONArray(d.name) ?: JSONArray().also { songs.put(d.name, it) }
                        files.forEach { f ->
                            arr.put(JSONObject().put("title", clean(f.nameWithoutExtension, d.name))
                                .put("file", "/local/$id/songs/${d.name}/${f.name}"))
                        }
                    }
                }
                (File(root, "lessons").listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }.forEach { d ->
                    val files = audioFiles(d)
                    if (files.isNotEmpty()) {
                        val name = d.name.substringBefore(" - ").trim()
                        val who = if (" - " in d.name) d.name.substringAfter(" - ").trim() else ""
                        val eps = JSONArray()
                        files.forEach { f ->
                            eps.put(JSONObject().put("title", f.nameWithoutExtension.trim())
                                .put("file", "/local/$id/lessons/${d.name}/${f.name}"))
                        }
                        lessons.put(JSONObject().put("name", name).put("who", who).put("episodes", eps))
                    }
                }
            } catch (e: Exception) { }
        }
        return JSONObject().put("songs", songs).put("lessons", lessons).toString()
    }

    /** تنزيل ملف مكتبة JSON من رابط (بدون قيود CORS) وإرجاعه للصفحة. */
    @JavascriptInterface
    fun fetchJson(url: String) {
        Thread {
            var out = ""
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                if (c.responseCode == 200) out = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
                c.disconnect()
            } catch (e: Exception) { }
            val js = "window.onLibRemote&&onLibRemote(" + JSONObject.quote(out) + ")"
            act.runOnUiThread { web.evaluateJavascript(js, null) }
        }.start()
    }
}

package com.islamic.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val appHost = "appassets.androidplatform.net"

    // أوامر الشريط العائم والأذان إلى الصفحة
    private val ctl = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cmd = i.getStringExtra("cmd") ?: return
            web.post { web.evaluateJavascript("window.nativeCtl&&nativeCtl('$cmd')", null) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN

        // تقديم index.html من https://appassets... ليعمل تضمين يوتيوب والخرائط بشكل سليم
        val offlineDir = File(filesDir, "offline").apply { mkdirs() }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/offline/", WebViewAssetLoader.InternalStoragePathHandler(this, offlineDir))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }

        // الجسر: يفتح يوتيوب الحقيقي في شاشة WebView كاملة
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun open(url: String) { runOnUiThread { openYouTube(url) } }
        }, "AndroidYT")

        web.addJavascriptInterface(OfflineBridge(this, web, offlineDir), "AndroidDL")
        web.addJavascriptInterface(NavBridge(this), "AndroidNav")
        web.addJavascriptInterface(PhoneBridge(this), "AndroidPhone")
        web.addJavascriptInterface(PrayerBridge(this), "AndroidPrayer")
        web.addJavascriptInterface(FloatBridge(this), "AndroidFloat")
        web.addJavascriptInterface(LibraryBridge(this, web), "AndroidLib")
        val filter = IntentFilter(CTRL_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ctl, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(ctl, filter)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                audioResponse(request, offlineDir) ?: loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == appHost) return false
                route(request.url)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // window.open(...) من الصفحة
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        route(r.url); return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }

            // موقعي في الخريطة
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin; geoCallback = callback
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                }
            }
        }

        web.loadUrl("https://$appHost/assets/index.html")
    }

    /** يخدم الملفات الصوتية المحلية مع دعم Range ليعمل التقديم والتأخير. */
    private fun audioResponse(request: WebResourceRequest, offlineDir: File): WebResourceResponse? {
        val path = request.url.path ?: return null
        val total: Long
        val open: (Long) -> InputStream
        if (path.startsWith("/offline/")) {
            val f = File(offlineDir, path.removePrefix("/offline/"))
            if (!f.isFile || !f.canonicalPath.startsWith(offlineDir.canonicalPath)) return null
            total = f.length()
            open = { start -> FileInputStream(f).also { it.channel.position(start) } }
        } else if (path.startsWith("/assets/audio/")) {
            val name = path.removePrefix("/assets/")
            total = try { assets.openFd(name).use { it.length } } catch (e: Exception) { return null }
            open = { start ->
                val st = assets.openFd(name).createInputStream()
                var left = start
                while (left > 0) { val k = st.skip(left); if (k <= 0) break; left -= k }
                st
            }
        } else if (path.startsWith("/local/")) {
            // ملفات أضافها المستخدم على الجهاز (Music/IslamicCompanion أو مجلد التطبيق)
            val rest = path.removePrefix("/local/")
            val rootId = rest.substringBefore('/')
            val rel = rest.substringAfter('/', "")
            val root = LibraryBridge.roots(this).firstOrNull { it.first == rootId }?.second ?: return null
            val f = File(root, rel)
            if (!f.isFile || !f.canonicalPath.startsWith(root.canonicalPath)) return null
            total = f.length()
            open = { start -> FileInputStream(f).also { it.channel.position(start) } }
        } else return null
        if (total <= 0) return null

        var start = 0L; var end = total - 1; var partial = false
        request.requestHeaders["Range"]?.let { r ->
            Regex("bytes=(\\d*)-(\\d*)").find(r)?.let { m ->
                val a = m.groupValues[1]; val b = m.groupValues[2]
                if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1) }
                else if (b.isNotEmpty()) start = maxOf(0L, total - b.toLong())
                partial = true
            }
        }
        if (start > end || start >= total) return null
        val len = end - start + 1
        val mime = when (path.substringAfterLast('.').lowercase()) {
            "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mpeg"
        }
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to len.toString())
        if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
        val body = object : FilterInputStream(open(start)) {
            var left = len
            override fun read(): Int { if (left <= 0) return -1; val r = super.read(); if (r >= 0) left--; return r }
            override fun read(b: ByteArray, off: Int, n: Int): Int {
                if (left <= 0) return -1
                val r = super.read(b, off, minOf(n.toLong(), left).toInt()); if (r > 0) left -= r; return r
            }
        }
        return WebResourceResponse(mime, null, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers, body)
    }

    private fun route(uri: Uri) {
        val host = uri.host ?: ""
        if (uri.scheme != "http" && uri.scheme != "https") return
        if (host.endsWith("youtube.com") || host == "youtu.be") openYouTube(uri.toString())
        else startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun openYouTube(url: String) {
        startActivity(Intent(this, YouTubeActivity::class.java).putExtra("url", url))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 3) { web.post { web.evaluateJavascript("window.onLibPerm&&onLibPerm()", null) }; return }
        if (code != 1) return
        val ok = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        geoCallback?.invoke(geoOrigin, ok, false)
        geoCallback = null; geoOrigin = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { try { unregisterReceiver(ctl) } catch (e: Exception) { }; web.destroy(); super.onDestroy() }
}

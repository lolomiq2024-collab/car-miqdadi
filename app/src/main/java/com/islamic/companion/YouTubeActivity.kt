package com.islamic.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*

/** شاشة يوتيوب الحقيقية داخل التطبيق: حساب، توصيات، بحث، ملء الشاشة. */
class YouTubeActivity : Activity() {

    private lateinit var web: WebView
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u: Uri = request.url
                if (u.scheme != "http" && u.scheme != "https") return true   // تجاهل روابط فتح تطبيق يوتيوب
                val h = u.host ?: ""
                val inside = h.endsWith("youtube.com") || h.endsWith("google.com") ||
                    h.endsWith("gstatic.com") || h.endsWith("googlevideo.com") || h == "youtu.be"
                if (!inside) { startActivity(Intent(Intent.ACTION_VIEW, u)); return true }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                cb.invoke(origin, true, false)
            }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                customView = v; customCb = cb
                (window.decorView as ViewGroup).addView(v)
                web.visibility = View.GONE
            }
            override fun onHideCustomView() {
                customView?.let { (window.decorView as ViewGroup).removeView(it) }
                customView = null; customCb?.onCustomViewHidden(); customCb = null
                web.visibility = View.VISIBLE
            }
        }

        web.loadUrl(intent.getStringExtra("url") ?: "https://m.youtube.com")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}

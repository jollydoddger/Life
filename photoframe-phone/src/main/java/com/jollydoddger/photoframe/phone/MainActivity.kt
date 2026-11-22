package com.jollydoddger.photoframe.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** The TV's own web app (Upload / Library / Settings / Remote), full screen, with the TV found automatically. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var panel: AddressPanel
    private lateinit var cover: FrameLayout
    private var connect: Connect? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var loadedUrl: String? = null

    companion object { private const val PICK = 1 }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(22, 24, 30))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) { loadedUrl = null; showPanel(); panel.askForAddress("Can't reach the TV.") ; retryLater() }
                }
            }
            webChromeClient = object : WebChromeClient() {
                // The Upload tab's "Choose files" button
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = cb
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    return runCatching { startActivityForResult(pick, PICK) }.map { true }.getOrElse { fileCallback = null; false }
                }
            }
        }
        panel = AddressPanel(this) { url -> open(url) }
        cover = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(22, 24, 30))
            addView(panel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                setMargins(dp(20), 0, dp(20), 0)
            })
        }
        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        askForNotifications()
        find()
    }

    private fun find() {
        showPanel()
        panel.searching()
        connect?.cancel()
        connect = Connect(this) { url -> if (url != null) open(url) else panel.askForAddress() }.also { it.start() }
    }

    /** While the "can't reach" panel is up, keep quietly looking: the TV may just be booting. */
    private fun retryLater() {
        connect?.cancel()
        web.postDelayed({ if (loadedUrl == null && !isFinishing) connect = Connect(this) { url -> if (url != null) open(url) }.also { it.start() } }, 5000)
    }

    private fun open(url: String) {
        if (loadedUrl == url && cover.visibility != View.VISIBLE) return
        connect?.cancel()
        loadedUrl = url
        cover.visibility = View.GONE
        web.loadUrl(url)
    }

    private fun showPanel() { cover.visibility = View.VISIBLE }

    override fun onResume() {
        super.onResume()
        // Coming back to the app: refresh the status pill (the page's own timer was paused)
        if (loadedUrl != null) web.evaluateJavascript("typeof loadStatus==='function'&&loadStatus()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK) return super.onActivityResult(requestCode, resultCode, data)
        val uris = when {
            resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cover.visibility != View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun askForNotifications() {
        // So uploads shared from Gallery can show their progress
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onDestroy() {
        connect?.cancel()
        web.destroy()
        super.onDestroy()
    }
}

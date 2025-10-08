package com.kangdigi.cbtman1medan

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingIndicator: ProgressBar

    private val permanentUrl = "https://cbt.man1medan.web.id/"
    private var backPressedOnce = false
    private val handler = Handler(Looper.getMainLooper())

    // Variabel untuk tap rahasia
    private var tapCount = 0
    private var lastTapTime: Long = 0
    private val REQUIRED_TAPS = 5
    private val TAP_TIMEOUT = 1000L

    // Deteksi keluar dari pinned mode
    private var wasPinned = false

    // Cek mode split screen
    private val securityCheckRunnable = object : Runnable {
        override fun run() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
                showExitDialog("Akses Diblokir", "Mode layar terbagi terdeteksi. Aplikasi akan ditutup.")
            } else {
                handler.postDelayed(this, 3000)
            }
        }
    }

    // --------------------- ONCREATE ---------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Tampilkan status bar & navigation bar hitam dengan ikon putih ---
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false  // ikon status bar putih
        insetsController.isAppearanceLightNavigationBars = false  // ikon navigation bar putih

        // --- Keamanan & kenyamanan layar ---
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        initViews()
        initWebView()
        loadWebViewContent()

        // Tombol back 2x untuk keluar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    if (backPressedOnce) {
                        finishAffinity()
                        return
                    }
                    backPressedOnce = true
                    Toast.makeText(this@MainActivity, "Tekan sekali lagi untuk keluar aplikasi", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ backPressedOnce = false }, 2000)
                }
            }
        })

        // Deteksi tap tersembunyi untuk keluar mode ujian
        val secretExitView: View = findViewById(R.id.secret_exit_view)
        secretExitView.setOnClickListener { handleSecretTap() }
    }

    private fun handleSecretTap() {
        val currentTime = System.currentTimeMillis()
        tapCount = if (currentTime - lastTapTime > TAP_TIMEOUT) 1 else tapCount + 1
        lastTapTime = currentTime
        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            Toast.makeText(this, "Mode Ujian Dihentikan", Toast.LENGTH_SHORT).show()
            stopLockTask()
            handler.postDelayed({ finishAffinity() }, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        startScreenPinning()
        handler.post(securityCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(securityCheckRunnable)
    }

    private fun startScreenPinning() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            startLockTask()
            wasPinned = true
        }
    }

    // --------------------- DETEKSI KELUAR PINNED ---------------------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (wasPinned && !hasFocus) {
            // Kemungkinan pengguna keluar dari pinned mode
            handler.postDelayed({
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    forceLogout()
                }
            }, 500)
        }
    }

    // --------------------- INISIALISASI ---------------------
    private fun initViews() {
        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh_layout)
        loadingIndicator = findViewById(R.id.loading_indicator)

        swipeRefresh.setOnApplyWindowInsetsListener { view, windowInsets -> windowInsets }

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingIndicator.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipeRefresh.isRefreshing = false
                loadingIndicator.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    loadingIndicator.visibility = View.GONE
                    showOfflinePage()
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        webView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun loadWebViewContent() {
        if (isInternetAvailable()) {
            handler.postDelayed({
                webView.loadUrl(permanentUrl)
            }, 100)
        } else {
            showOfflinePage()
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOfflinePage() {
        webView.loadUrl("file:///android_asset/offline.html")
    }

    private fun forceLogout() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(permanentUrl)
            Toast.makeText(this, "Akses dihentikan — silakan login ulang", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showExitDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Keluar") { _, _ -> finishAffinity() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView.clearCache(true)
            webView.clearHistory()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

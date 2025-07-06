package club.ppmc.webshell

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val targetUrl = "https://ppmc.club/ppmc.html"

    // [REMOVED] 不再需要 MediaProjection 和 Service 相关的启动器和广播接收器
    // private lateinit var mediaProjectionManager: MediaProjectionManager
    // private var screenshotBroadcastReceiver: BroadcastReceiver? = null
    // ... 等相关代码

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var uris: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        }
        filePathCallback!!.onReceiveValue(uris)
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) {
                allGranted = false
                Log.w("Permissions", "${it.key} not granted")
            }
        }
        if (allGranted) {
            Toast.makeText(this, "所有必要权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "部分核心权限未授予，应用功能可能受限。", Toast.LENGTH_LONG).show()
        }
        loadWebView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        // [REMOVED] 不再需要初始化 MediaProjection 或注册广播
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            loadWebView()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun saveFile(jsonData: String, fileName: String) {
            // ... saveFile 方法保持不变 ...
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(jsonData.toByteArray(Charsets.UTF_8))
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "文件已保存到 '下载' 目录", Toast.LENGTH_LONG).show()
                        }
                    } ?: throw Exception("MediaStore.insert() 返回了 null")
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val file = java.io.File(downloadsDir, fileName)
                    file.writeText(jsonData, Charsets.UTF_8)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "文件已保存到 '下载' 目录", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("WebAppInterface", "保存文件失败", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // --- [NEW] 使用 PixelCopy 实现的全新截图方法 ---
        @JavascriptInterface
        fun startScreenCapture() {
            // 确保在主线程执行
            runOnUiThread {
                takeScreenshotOfView(webView) { success, bitmap ->
                    if (success && bitmap != null) {
                        // 将 Bitmap 转换为 Base64 字符串
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val base64String = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                        // 构造 Data URL 并传递给 WebView
                        val dataUrl = "data:image/png;base64,$base64String"
                        val jsCallback = "javascript:window.handleNativeScreenshot('${dataUrl.replace("'", "\\'")}')"
                        webView.evaluateJavascript(jsCallback, null)
                    } else {
                        // 截图失败，通知 WebView
                        val jsErrorCallback = "javascript:if(typeof NotificationUIManager !== 'undefined') { NotificationUIManager.showNotification('截图失败，请重试。', 'error'); }"
                        webView.evaluateJavascript(jsErrorCallback, null)
                        Toast.makeText(this@MainActivity, "截图失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * [NEW] 使用 PixelCopy 截取指定 View 的内容。
     * @param view 要截取的 View (例如 WebView)。
     * @param callback 操作完成后的回调，返回成功状态和 Bitmap。
     */
    private fun takeScreenshotOfView(view: View, callback: (Boolean, Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 使用 PixelCopy API (Android 8.0+)，性能更好
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val locationOfViewInWindow = IntArray(2)
            view.getLocationInWindow(locationOfViewInWindow)

            try {
                PixelCopy.request(
                    window,
                    android.graphics.Rect(locationOfViewInWindow[0], locationOfViewInWindow[1], locationOfViewInWindow[0] + view.width, locationOfViewInWindow[1] + view.height),
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            callback(true, bitmap)
                        } else {
                            Log.e("PixelCopy", "Failed to copy: $copyResult")
                            callback(false, null)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: IllegalArgumentException) {
                // The destination isn't a valid copy target.
                Log.e("PixelCopy", "PixelCopy failed", e)
                callback(false, null)
            }
        } else {
            // 使用旧的 Drawing Cache 方法作为备选 (Android 7.1 及以下)
            try {
                @Suppress("DEPRECATION")
                view.isDrawingCacheEnabled = true
                @Suppress("DEPRECATION")
                val bmp = Bitmap.createBitmap(view.drawingCache)
                @Suppress("DEPRECATION")
                view.isDrawingCacheEnabled = false
                callback(true, bmp)
            } catch (e: Exception) {
                Log.e("DrawingCache", "Failed to create bitmap from drawing cache", e)
                callback(false, null)
            }
        }
    }

    private fun loadWebView() {
        // ... loadWebView 方法保持不变 ...
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString + " WebViewApp/1.0"
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_LONG).show()
                    return false
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimetype)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            request.setTitle(fileName)
            request.setDescription("正在下载文件...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            try {
                downloadManager.enqueue(request)
                Toast.makeText(applicationContext, "开始下载: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("Download", "无法开始下载", e)
                Toast.makeText(applicationContext, "无法开始下载", Toast.LENGTH_SHORT).show()
            }
        }
        webView.loadUrl(targetUrl)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // [REMOVED] 不再需要注销广播接收器
        super.onDestroy() // super.onDestroy() 应该在最后调用
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    }
}
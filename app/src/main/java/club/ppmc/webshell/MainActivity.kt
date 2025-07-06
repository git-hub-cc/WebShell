package club.ppmc.webshell

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val targetUrl = "https://ppmc.club/ppmc.html"

    // 用于处理文件选择器 <input type="file"> 的回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ActivityResultLauncher 用于启动文件选择器并接收结果
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult

        var uris: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            // FileChooserParams.parseResult 会处理单个文件和多个文件的情况
            uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        }

        filePathCallback!!.onReceiveValue(uris)
        filePathCallback = null
    }


    // 权限请求器
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
            loadWebView()
        } else {
            Toast.makeText(this, "部分核心权限未授予，应用功能可能受限。", Toast.LENGTH_LONG).show()
            loadWebView() // 仍然加载，网页应处理权限不足的情况
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // *** START: MODIFIED SECTION ***
        // 创建一个可变列表来存放需要请求的权限
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // 根据 Android 版本决定是否添加存储权限
        // Manifest 中已设置 maxSdkVersion="28"，所以只在 API 28 及以下版本请求
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        // *** END: MODIFIED SECTION ***

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Toast.makeText(this, "所有核心权限已具备", Toast.LENGTH_SHORT).show()
            loadWebView()
        }
    }

    /**
     * JavaScript 接口类，提供给 WebView 调用原生功能
     * 用于处理由JS生成内容并需要保存为文件的场景
     */
    inner class WebAppInterface {
        @JavascriptInterface // 这个注解是必须的，表示该方法可以被 JS 调用
        fun saveFile(jsonData: String, fileName: String) {
            // 在安卓 10 (API 29) 及以上，直接写入公共目录（如Downloads）的最佳实践是使用 MediaStore。
            // 但为了兼容性和简单性，这里使用传统的文件 API。这在 Android 10+ 可能需要特殊配置
            // (如在 Manifest 中设置 android:requestLegacyExternalStorage="true")，
            // 或更好的是改用 MediaStore 或 Storage Access Framework。
            // 对于 targetSdkVersion 28 或更低，此方法结合 Manifest 中的权限通常能工作。
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = java.io.File(downloadsDir, fileName)

                // 将 JS 传递过来的字符串数据写入文件
                file.writeText(jsonData, Charsets.UTF_8)

                // 在主线程上显示提示，因为 JS 接口运行在后台线程
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "文件已保存到 '下载' 目录", Toast.LENGTH_LONG).show()
                    Log.i("WebAppInterface", "文件成功保存: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e("WebAppInterface", "保存文件失败", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun loadWebView() {
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

        // *** 关键改动：添加 JavaScript 接口 ***
        // "Android" 是 JS 调用时使用的对象名，例如 window.Android.saveFile(...)
        webView.addJavascriptInterface(WebAppInterface(), "Android")


        webView.webViewClient = object : WebViewClient() {
            // ... (webViewClient 的实现保持不变)
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // ... (webChromeClient 的实现保持不变)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
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

        // 这个监听器仍然有用，用于处理真正的网络文件下载（非 blob: URL）
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
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}

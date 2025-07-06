package club.ppmc.webshell

import android.Manifest
import android.app.Activity // Import Activity
import android.content.ActivityNotFoundException // Import ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri // Import Uri
import android.net.http.SslError
import android.os.Bundle
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
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
            // 注意：常规的文件选择不需要额外的存储权限 (READ/WRITE_EXTERNAL_STORAGE)
            // 系统文件选择器会处理权限。
            // WRITE_EXTERNAL_STORAGE (maxSdkVersion=28) 在 Manifest 中用于旧版 Android。
        )

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Toast.makeText(this, "所有核心权限已具备", Toast.LENGTH_SHORT).show()
            loadWebView()
        }
    }

    private fun loadWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true // 允许 WebView 访问文件系统中的文件, 对 file:/// URLs 很重要
            allowContentAccess = true // 允许 WebView 从 ContentProvider 加载内容

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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val errorCode = error?.errorCode ?: 0
                    val description = error?.description ?: "Unknown error"
                    Log.e("WebViewError", "Error: $errorCode - $description on URL: ${request.url}")
                    Toast.makeText(
                        this@MainActivity,
                        "页面加载失败: $description", // 更友好的提示
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                val errorMessage = "SSL Error: ${error?.toString()}"
                Log.e("SSL_ERROR", errorMessage + " for URL: " + error?.url)
                Toast.makeText(this@MainActivity, "SSL证书错误，请检查网络安全配置", Toast.LENGTH_LONG).show()
                handler?.cancel() // 取消加载，依赖 network_security_config.xml
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // 处理网页中的 <input type="file">
            override fun onShowFileChooser(
                mWebView: WebView,
                mFilePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // 如果之前有回调，取消它
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = mFilePathCallback

                val intent = fileChooserParams.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.e("FileChooser", "Cannot open file chooser", e)
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_LONG).show()
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false // 表示我们未能处理该请求
                }
                return true // 表示我们已经处理了该请求
            }


            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    val requestedResources = it.resources
                    val permissionsToGrantInWebView = mutableListOf<String>() // Renamed to avoid conflict
                    var allPermissionsAvailableForWebView = true // Renamed

                    requestedResources.forEach { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    permissionsToGrantInWebView.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                } else {
                                    allPermissionsAvailableForWebView = false
                                }
                            }
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    permissionsToGrantInWebView.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                } else {
                                    allPermissionsAvailableForWebView = false
                                }
                            }
                            // 可以添加对其他资源类型的处理
                        }
                    }

                    if (allPermissionsAvailableForWebView && permissionsToGrantInWebView.isNotEmpty()) {
                        it.grant(permissionsToGrantInWebView.toTypedArray())
                    } else {
                        it.deny()
                        Toast.makeText(this@MainActivity, "网页请求的摄像头或麦克风权限不足", Toast.LENGTH_SHORT).show()
                        // 如果权限不足，可以考虑再次触发原生权限请求
                        // checkAndRequestPermissions() // 可能会导致循环，谨慎使用
                    }
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }

            // 如果网页需要地理位置，需要实现这个回调并请求原生权限 (并确保Manifest中有位置权限)
            /*
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                // ... (需要实现位置权限请求逻辑)
                // callback?.invoke(origin, true, false) // 如果授予
                // callback?.invoke(origin, false, false) // 如果拒绝
            }
            */
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
        // 移除 WebView, 释放资源
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false // 禁用 JS 可能有助于资源释放
        webView.clearHistory()
        // webView.clearCache(true) // 按需清理缓存
        webView.removeAllViews() // 移除所有子视图
        webView.destroy()
        super.onDestroy()
    }
}
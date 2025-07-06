package club.ppmc.webshell

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayOutputStream
import java.io.File

// [MODIFIED] 主 Activity，增加了服务启动、Intent 处理和与 JS 交互的逻辑
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val targetUrl = "https://ppmc.club/ppmc.html"

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

    // [NEW] 用于接收来自 CallNotificationReceiver 的“拒绝”操作的广播
    private val declineCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallNotificationReceiver.ACTION_DECLINE_CALL_FROM_ACTIVITY) {
                val peerId = intent.getStringExtra(CallNotificationReceiver.EXTRA_PEER_ID)
                Log.d("MainActivity", "接收到拒绝广播，peerId: $peerId")
                if (peerId != null) {
                    // 在 WebView 中执行 JavaScript 挂断通话
                    val jsCode = "if(typeof VideoCallManager !== 'undefined') { VideoCallManager.hangUpMedia(true); }"
                    webView.evaluateJavascript(jsCode, null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        startCallService()
        checkAndRequestPermissions()
        handleIntent(intent) // 处理 Activity 通过通知启动的情况
    }

    override fun onResume() {
        super.onResume()
        // 注册本地广播接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(
            declineCallReceiver,
            IntentFilter(CallNotificationReceiver.ACTION_DECLINE_CALL_FROM_ACTIVITY)
        )
    }

    override fun onPause() {
        super.onPause()
        // 注销本地广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(declineCallReceiver)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent) // 处理 Activity 已在运行时通过通知启动的情况
    }

    private fun startCallService() {
        val serviceIntent = Intent(this, CallService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == CallNotificationReceiver.ACTION_ACCEPT_CALL) {
            val peerId = intent.getStringExtra(CallNotificationReceiver.EXTRA_PEER_ID)
            Log.d("MainActivity", "接收到接听 Intent, peerId: $peerId")
            if (peerId != null) {
                // 等待 WebView 加载完成后执行 JS，或立即执行
                // 为防止 race condition，最好让 JS 在 ready 后检查一个全局变量
                val jsCode = "if(typeof VideoCallHandler !== 'undefined') { VideoCallHandler.acceptCall('$peerId'); } else { window.pendingCallAccept = '$peerId'; }"
                webView.evaluateJavascript(jsCode, null)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // [NEW] 为 Android 13+ 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val file = File(downloadsDir, fileName)
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

        @JavascriptInterface
        fun startScreenCapture() {
            runOnUiThread {
                takeScreenshotOfView(webView) { success, bitmap ->
                    if (success && bitmap != null) {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val base64String = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        val dataUrl = "data:image/png;base64,$base64String"
                        val jsCallback = "javascript:window.handleNativeScreenshot('${dataUrl.replace("'", "\\'")}')"
                        webView.evaluateJavascript(jsCallback, null)
                    } else {
                        val jsErrorCallback = "javascript:if(typeof NotificationUIManager !== 'undefined') { NotificationUIManager.showNotification('截图失败，请重试。', 'error'); }"
                        webView.evaluateJavascript(jsErrorCallback, null)
                        Toast.makeText(this@MainActivity, "截图失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // --- [NEW] JS 调用此方法以显示来电通知 ---
        @JavascriptInterface
        fun showIncomingCall(callerName: String, peerId: String) {
            val intent = Intent(this@MainActivity, CallService::class.java).apply {
                action = CallService.ACTION_INCOMING_CALL
                putExtra(CallService.EXTRA_CALLER_NAME, callerName)
                putExtra(CallService.EXTRA_PEER_ID, peerId)
            }
            startService(intent)
        }

        // --- [NEW] JS 调用此方法以取消来电通知 ---
        @JavascriptInterface
        fun cancelIncomingCall() {
            val intent = Intent(this@MainActivity, CallService::class.java).apply {
                action = CallService.ACTION_CANCEL_CALL_NOTIFICATION
            }
            startService(intent)
        }
    }

    private fun takeScreenshotOfView(view: View, callback: (Boolean, Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                Log.e("PixelCopy", "PixelCopy failed", e)
                callback(false, null)
            }
        } else {
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
        super.onDestroy()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    }
}

// --- [NEW] 后台服务，用于保持应用存活和管理通知 ---
class CallService : Service() {

    companion object {
        const val ACTION_INCOMING_CALL = "club.ppmc.webshell.INCOMING_CALL"
        const val ACTION_CANCEL_CALL_NOTIFICATION = "club.ppmc.webshell.CANCEL_CALL_NOTIFICATION"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_PEER_ID = "peer_id"

        private const val FOREGROUND_CHANNEL_ID = "CallServiceChannel"
        private const val INCOMING_CALL_CHANNEL_ID = "IncomingCallChannel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        // [FIXED] 移除了 private，使其对 CallNotificationReceiver 可见
        const val INCOMING_CALL_NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_CALL -> {
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "未知来电"
                val peerId = intent.getStringExtra(EXTRA_PEER_ID)
                if (peerId != null) {
                    showIncomingCallNotification(callerName, peerId)
                }
            }
            ACTION_CANCEL_CALL_NOTIFICATION -> {
                cancelIncomingCallNotification()
            }
            else -> {
                // 这是服务第一次启动时
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            }
        }
        return START_STICKY
    }

    private fun showIncomingCallNotification(callerName: String, peerId: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = CallNotificationReceiver.ACTION_ACCEPT_CALL
            putExtra(CallNotificationReceiver.EXTRA_PEER_ID, peerId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, CallNotificationReceiver::class.java).apply {
            action = CallNotificationReceiver.ACTION_ACCEPT_CALL
            putExtra(CallNotificationReceiver.EXTRA_PEER_ID, peerId)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallNotificationReceiver::class.java).apply {
            action = CallNotificationReceiver.ACTION_DECLINE_CALL
            putExtra(CallNotificationReceiver.EXTRA_PEER_ID, peerId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setContentTitle("来电提醒")
            .setContentText("$callerName 正在呼叫您...")
            .setSmallIcon(R.mipmap.ic_launcher) // 请确保你有这个图标
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true) // 全屏意图，实现来电界面
            .addAction(0, "接听", acceptPendingIntent)
            .addAction(0, "拒绝", declinePendingIntent)
            .setAutoCancel(true)
            .setOngoing(true) // 使通知不能被轻易滑掉
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    private fun cancelIncomingCallNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("服务正在后台运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "后台服务",
                NotificationManager.IMPORTANCE_MIN
            )
            val incomingCallChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "来电提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示来电的全屏通知"
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(incomingCallChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// --- [NEW] 广播接收器，处理通知上的按钮点击事件 ---
class CallNotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT_CALL = "club.ppmc.webshell.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "club.ppmc.webshell.DECLINE_CALL"
        const val ACTION_DECLINE_CALL_FROM_ACTIVITY = "club.ppmc.webshell.DECLINE_CALL_INTERNAL"
        const val EXTRA_PEER_ID = "peer_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(EXTRA_PEER_ID)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(CallService.INCOMING_CALL_NOTIFICATION_ID)

        when (intent.action) {
            ACTION_ACCEPT_CALL -> {
                Log.d("CallReceiver", "接听操作，启动 MainActivity")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_ACCEPT_CALL
                    putExtra(EXTRA_PEER_ID, peerId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            }
            ACTION_DECLINE_CALL -> {
                Log.d("CallReceiver", "拒绝操作，发送本地广播")
                val localIntent = Intent(ACTION_DECLINE_CALL_FROM_ACTIVITY).apply {
                    putExtra(EXTRA_PEER_ID, peerId)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent)
            }
        }
    }
}
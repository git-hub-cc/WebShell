# PPMC WebShell

这是一个功能强大且配置完善的安卓 WebView 应用模板。它不仅仅是一个简单的网页加载器，而是一个功能齐全的 "Web Shell"，旨在将任何现代 Web 应用无缝封装成本机安卓应用。

该项目演示了如何处理 Web 与安卓原生功能之间的常见交互，包括权限请求、文件上传和文件下载。

---

## ✨ 主要功能

-   **完整的网页容器**: 经过优化的 `WebView` 设置，支持 JavaScript、DOM 存储、缓存、缩放和响应式布局 (`useWideViewPort`)，提供流畅的类浏览器体验。
-   **动态权限处理**:
    -   在应用启动时，使用现代的 `ActivityResultLauncher` 请求**摄像头**和**麦克风**权限。
    -   当网页（例如通过 WebRTC）请求访问摄像头或麦克风时，能正确地将已授予的原生权限传递给 `WebView`。
-   **文件上传支持**: 完整实现了对网页中 `<input type="file">` 标签的支持。点击该标签会触发安卓系统的文件选择器，用户可以选择单个或多个文件，并将结果安全地返回给网页。
-   **文件下载支持**: 实现了对文件下载的处理。当用户点击下载链接时，会自动调用系统下载管理器来下载文件，并将文件保存在公共的“下载”目录中，同时在通知栏显示下载进度。
-   **优雅的错误处理**: 能够捕获页面加载错误和 SSL 证书错误，并向用户显示友好的提示信息。
-   **返回键导航**: 在 `WebView` 中支持使用物理返回键进行历史记录后退，如果无法后退，则退出应用。
-   **生命周期管理**: 在 `onDestroy` 中正确清理和销毁 `WebView`，防止内存泄漏。

---

## ⚙️ 工作原理

1.  **`MainActivity.kt`**: 应用的核心。它负责初始化 `WebView`、请求必要的权限，并配置所有的客户端和监听器。
2.  **权限管理 (`permissionLauncher`)**: 使用 `ActivityResultContracts.RequestMultiplePermissions` 在应用启动时请求一组预定义的权限（相机、录音）。
3.  **`WebChromeClient`**:
    -   `onShowFileChooser`: 拦截 `<input type="file">` 事件，启动系统文件选择器 (`fileChooserLauncher`)，并将用户选择的文件 URI 返回给网页。
    -   `onPermissionRequest`: 拦截来自网页的权限请求（如 `getUserMedia`），检查原生应用是否已获得相应权限（如 `CAMERA`），然后授予或拒绝该请求。
4.  **`WebViewClient`**:
    -   处理页面导航事件，如 `onPageStarted` 和 `onPageFinished`。
    -   `onReceivedError` 和 `onReceivedSslError` 用于捕获网络或安全错误。
5.  **下载监听器 (`setDownloadListener`)**:
    -   当 `WebView` 识别到一个下载链接时，此监听器被触发。
    -   它会创建一个 `DownloadManager.Request`，并使用安卓系统的 `DownloadManager` 服务来处理下载。这提供了标准的系统级下载体验，包括通知和后台处理。
6.  **`AndroidManifest.xml`**:
    -   声明应用所需的所有权限，如 `INTERNET`、`CAMERA`、`RECORD_AUDIO` 和 `WRITE_EXTERNAL_STORAGE`（用于旧版安卓的文件下载）。
    -   `android:usesCleartextTraffic="true"` 允许在开发或特定情况下加载 HTTP 资源。为了生产安全，建议配置网络安全配置（Network Security Configuration）。

---

## 🚀 如何开始

1.  **克隆仓库**:
    ```bash
    git clone <your-repository-url>
    ```
2.  **修改目标 URL**:
    -   打开 `app/src/main/java/club/ppmc/webshell/MainActivity.kt`。
    -   修改 `targetUrl` 变量为你自己的网页地址：
        ```kotlin
        private val targetUrl = "https://your-website.com"
        ```
3.  **构建和运行**:
    -   使用 Android Studio 打开项目。
    -   等待 Gradle 同步完成。
    -   点击 "Run 'app'" 按钮，在模拟器或真实设备上部署应用。

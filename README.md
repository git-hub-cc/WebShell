# PPMC WebShell

This is a powerful and well-configured Android WebView application template. It serves as more than just a simple web page loader; it's a feature-complete "Web Shell" designed to seamlessly wrap any modern web application into a native Android app.

This project demonstrates how to handle common interactions between the web content and native Android capabilities, including permission requests, file uploads, and file downloads.

---

## ✨ Key Features

-   **Complete Web App Container**: Optimized `WebView` settings support JavaScript, DOM storage, caching, zooming, and responsive layouts (`useWideViewPort`), providing a smooth, browser-like experience.
-   **Dynamic Permission Handling**:
    -   Uses the modern `ActivityResultLauncher` to request **Camera** and **Microphone** permissions on app startup.
    -   Correctly delegates granted native permissions to the `WebView` when the web page (e.g., via WebRTC) requests access to the camera or microphone.
-   **File Upload Support**: Fully implements support for the `<input type="file">` tag in web pages. Clicking it triggers the native Android file chooser, allowing users to select single or multiple files and securely return the result to the web page.
-   **File Download Support**: Handles file downloads initiated from the webpage. Clicking a download link will automatically delegate the download to Android's system `DownloadManager`, saving the file to the public "Downloads" directory and showing progress in the notification bar.
-   **Graceful Error Handling**: Catches page load errors and SSL certificate errors, displaying user-friendly toast messages.
-   **Back Key Navigation**: Supports using the physical back button to navigate back in the `WebView`'s history. If no history is available, it exits the app.
-   **Lifecycle Management**: Properly cleans up and destroys the `WebView` in `onDestroy` to prevent memory leaks.

---

## ⚙️ How It Works

1.  **`MainActivity.kt`**: The core of the application. It initializes the `WebView`, requests necessary permissions, and configures all clients and listeners.
2.  **Permission Management (`permissionLauncher`)**: Uses `ActivityResultContracts.RequestMultiplePermissions` to request a predefined set of permissions (Camera, Record Audio) at startup.
3.  **`WebChromeClient`**:
    -   `onShowFileChooser`: Intercepts `<input type="file">` events, launches the system file chooser (`fileChooserLauncher`), and returns the user-selected file URIs to the web page.
    -   `onPermissionRequest`: Intercepts permission requests from the web page (like `getUserMedia`), checks if the native app has the corresponding permissions (e.g., `CAMERA`), and then grants or denies the request accordingly.
4.  **`WebViewClient`**:
    -   Handles page navigation events like `onPageStarted` and `onPageFinished`.
    -   `onReceivedError` and `onReceivedSslError` are used to catch network or security-related errors.
5.  **Download Listener (`setDownloadListener`)**:
    -   This listener is triggered when the `WebView` identifies a download link.
    -   It creates a `DownloadManager.Request` and uses the Android system's `DownloadManager` service to handle the download. This provides a standard, system-level download experience with notifications and background processing.
6.  **`AndroidManifest.xml`**:
    -   Declares all permissions required by the app, such as `INTERNET`, `CAMERA`, `RECORD_AUDIO`, and `WRITE_EXTERNAL_STORAGE` (for file downloads on older Android versions).
    -   `android:usesCleartextTraffic="true"` allows loading HTTP resources for development or specific cases. For production security, configuring a Network Security Configuration is recommended.

---

## 🚀 Getting Started

1.  **Clone the Repository**:
    ```bash
    git clone <your-repository-url>
    ```
2.  **Change the Target URL**:
    -   Open `app/src/main/java/club/ppmc/webshell/MainActivity.kt`.
    -   Modify the `targetUrl` variable to point to your own web application:
        ```kotlin
        private val targetUrl = "https://your-website.com"
        ```
3.  **Build and Run**:
    -   Open the project in Android Studio.
    -   Wait for Gradle to sync.
    -   Click the "Run 'app'" button to deploy the application on an emulator or a physical device.
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.BrowserStorage
import com.mengpaw.browser.util.PdfUtil
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.kernel.KernelLog
import com.mengpaw.design.theme.ArcoTheme

/**
 * 浏览器独立 APK 入口。
 *
 * v0.32.x (400 行文件拆分批次 2): 主 UI (BrowserApp) 拆至 [BrowserApp.kt] +
 * 对话框层 [BrowserAppDialogs.kt]。9880 桥退役 (决策 #7) 后 McpHttpServer/BrowserMcpTools 已删除,
 * Agent 控制统一走 am 桥 (RunCommandService)。
 */
class BrowserActivity : ComponentActivity() {

    // ── 累计修改（站点兼容性）: 文件选择 / 全屏视频 / 运行时权限 三处宿主能力 ──

    /** 站点 `<input type=file>` 待回填的回调（一次一个）。 */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /** ActivityResult 启动器必须在 STARTED 之前注册 —— 字段初始化即注册。 */
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    /** 站点请求的运行时权限 → 一次性回调（把结果转成"是否全部授予"）。 */
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    /** 全屏视频宿主（添加在内容视图之上，退出时移除）。 */
    private var fullscreenView: android.view.View? = null
    private var fullscreenContainer: android.widget.FrameLayout? = null
    private var fullscreenCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    /** 当前是否处于全屏视频（WebView 侧避免重复进入）。 */
    internal fun isFullscreenActive(): Boolean = fullscreenView != null

    /**
     * 站点 `<input type=file>` / 上传按钮 → 系统文件选择器。
     * 未实现时任何"上传图片/附件"按钮点了毫无反应（Edge/Chrome 均支持）。
     */
    internal fun launchFileChooser(
        params: android.webkit.WebChromeClient.FileChooserParams,
        cb: android.webkit.ValueCallback<Array<android.net.Uri>>
    ): Boolean {
        return try {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = cb
            fileChooserLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            pendingFileCallback = null
            Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** 站点请求运行时权限（摄像头/麦克风/定位）→ 系统授权弹窗。 */
    internal fun requestRuntimePermissions(perms: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onResult(true); return }
        // 系统同一时刻只处理一个权限请求：若已有在飞，明确拒绝后来者，
        // 否则覆盖回调会让后一个站点的 Promise 永久 pending（审查 P2-6）
        if (pendingPermissionCallback != null) {
            android.util.Log.w("BrowserActivity", "已有权限请求在飞，直接拒绝本次请求")
            onResult(false)
            return
        }
        pendingPermissionCallback = onResult
        try {
            requestPermissions(missing.toTypedArray(), REQ_SITE_PERMISSIONS)
        } catch (_: Exception) {
            pendingPermissionCallback = null
            onResult(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SITE_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val cb = pendingPermissionCallback
        pendingPermissionCallback = null
        cb?.invoke(granted)
    }

    /** 进入全屏视频（站点 `video.requestFullscreen()` / WebChromeClient.onShowCustomView）。 */
    internal fun enterFullscreen(
        view: android.view.View,
        callback: android.webkit.WebChromeClient.CustomViewCallback? = null
    ) {
        // 已在全屏: 通知本次回调已隐藏（否则 WebView 侧状态与页面对不上 —— 审查 P2-8）
        if (fullscreenView != null) { callback?.onCustomViewHidden(); return }
        val parent = window.decorView as? android.view.ViewGroup
        if (parent == null) { callback?.onCustomViewHidden(); return }
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        try {
            parent.addView(container, android.view.ViewGroup.LayoutParams(-1, -1))
        } catch (e: Exception) {
            android.util.Log.e("BrowserActivity", "全屏容器添加失败", e)
            callback?.onCustomViewHidden()
            return
        }
        // addView 成功后才置状态（失败时不能留下"看似全屏"的脏状态）
        fullscreenContainer = container
        fullscreenView = view
        fullscreenCallback = callback
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 沉浸式: 隐藏状态栏/导航栏（退出时经 WindowInsetsController 恢复）
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /** 退出全屏视频（幂等: 可被返回键/onHideCustomView/onDestroy 多次调用）。 */
    internal fun exitFullscreen() {
        val container = fullscreenContainer
        // 先取走并清空回调，保证幂等且不会重复回调 WebView
        val callback = fullscreenCallback
        fullscreenCallback = null
        fullscreenContainer = null
        val hadView = fullscreenView != null
        fullscreenView = null
        if (container != null) {
            try { (container.parent as? android.view.ViewGroup)?.removeView(container) } catch (_: Exception) {}
        }
        if (hadView) {
            try { callback?.onCustomViewHidden() } catch (_: Exception) {}
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
            enableEdgeToEdge()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataPathsInitializer.initialize(this)
        KernelLog.setLogger(AndroidLogger())
        // v0.10.3: 清理过期 PDF 预览缓存（旧实现每次预览都往 cacheDir 写且从不删除）
        try { PdfUtil.cleanupStaleCache(this) } catch (_: Exception) { }
        // 9880 HTTP 桥已退役 (半自动武器决策 #7): Agent 控制统一走 am 桥 (RunCommandService,
        // signature 权限), 无 token/开放模式/HTTP server。
        val prefs = BrowserPrefs(this)
        // Bind screenshot settings to BuiltinBrowserPlugin
        // (v0.10.2: 移除 quickClick 接线 — 该开关全链路无读取点, 属无效控件)
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.screenshotMaxHeight = { prefs.screenshotMaxHeight }
        // am 桥共享实例（Phase 2）— RunCommandService 经 signature 权限调用同一命令引擎
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.shared = builtinBrowserPlugin
        // 半自动武器方案决策 #2: 首次打开弹窗引导授予「所有文件访问」(截图落公共目录)
        ensureStoragePermission()
        enableEdgeToEdge()
        // Read theme from first Agent's theme.md (or default)
        val themeConfig = BrowserThemeConfig.load(this)
        val isDark = (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Check for .md file intent
        val mdFileContent = checkMdFile(intent)
        // Check for local .pdf file intent (file/content) → pdf.js 预览
        val pdfIntent = pdfSourceFromIntent(intent)
        setContent {
            ArcoTheme(darkTheme = isDark) {
                BrowserApp(
                    initialUrl = extractUrl(intent),
                    initialMdContent = mdFileContent,
                    initialPdfSource = pdfIntent?.first,
                    initialPdfTitle = pdfIntent?.second.orEmpty()
                )
            }
        }
    }

    /** 决策 #2: 首次打开引导授予 MANAGE_EXTERNAL_STORAGE（API 30+ 跳系统设置页，非运行时弹窗）。 */
    private fun ensureStoragePermission() {
        if (BrowserStorage.hasStorageAccess()) return
        val prefs = getSharedPreferences("mp_browser", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("storage_permission_prompted", false)) return
        prefs.edit().putBoolean("storage_permission_prompted", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage(
                "MP 浏览器需要「所有文件访问」权限，用于把截图保存到公共目录\n" +
                    "(MengPaw/截图存档)，Agent 才能读取并分析。\n\n" +
                    "点击「去授权」后在系统设置中允许访问所有文件。"
            )
            .setPositiveButton("去授权") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        // 设备不支持跳转 — 放弃引导，命令层会持续提示（决策 #6）
                    }
                }
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    /**
     * 发送 URL (或网页提炼请求) 给 Shell 的 MengPaw Agent。
     * extract=true 时加 mode=extract + title, Shell 会直接触发 Agent 提炼并回传。
     * internal: 顶层 BrowserApp 回调经 (ctx as? BrowserActivity) 调用。
     */
    internal fun sendToAgent(url: String, title: String, extract: Boolean) {
        val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
            setClassName("com.mengpaw.shell", "com.mengpaw.shell.MainActivity")
            putExtra("url", url)
            if (extract) {
                putExtra("mode", "extract")
                putExtra("title", title.ifBlank { url })
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "MengPaw 未安装", Toast.LENGTH_SHORT).show()
        }
    }

    /** 读取 OPEN_MD 的 md 内容 (extra 或 FileProvider URI)。 */
    private fun readMdUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val resolver = contentResolver
            val input = resolver.openInputStream(android.net.Uri.parse(uriString)) ?: return null
            input.bufferedReader().use { it.readText().take(500_000) }
        } catch (_: Exception) { null }
    }

    /** 处理浏览器 APK 收到的外部 Intent (OPEN_URL 重复打开 / OPEN_MD 提炼回传)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            "com.mengpaw.action.OPEN_URL" -> onOpenUrl?.invoke(extractUrl(intent))
            "com.mengpaw.action.OPEN_MD" -> {
                val md = intent.getStringExtra("md") ?: readMdUri(intent.getStringExtra("mdUri")) ?: return
                onOpenMd?.invoke(intent.getStringExtra("title") ?: "", intent.getStringExtra("url") ?: "", md)
            }
            android.content.Intent.ACTION_VIEW -> {
                val pdf = pdfSourceFromIntent(intent)
                if (pdf != null) {
                    onOpenPdf?.invoke(pdf.first, pdf.second)
                } else {
                    val md = checkMdFile(intent)
                    if (md != null) onOpenMd?.invoke("", "", md)
                }
            }
        }
    }

    /** Check if the intent carries a Markdown file and return its content, or null. */
    private fun checkMdFile(intent: Intent?): String? {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        // file:// — 直接读文件; content:// — 经 ContentResolver (FileProvider 共享 / SAF 选择)
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                if (!path.endsWith(".md", ignoreCase = true) && intent.type != "text/markdown") return null
                try {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) file.readText().take(500_000) else null
                } catch (_: Exception) { null }
            }
            "content" -> if (intent.type != "text/plain" || uri.toString().endsWith(".md", ignoreCase = true)) readMdUri(uri.toString()) else null
            else -> null
        }
    }

    /** 本地 .pdf VIEW intent (file:// 或 content://) → (源 uri 串, 显示名); 非 pdf 返回 null。 */
    private fun pdfSourceFromIntent(intent: Intent?): Pair<String, String>? {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return null
        val uri = intent.data?.toString() ?: return null
        if (!PdfUtil.isLocalPdf(uri, intent.type)) return null
        return uri to PdfUtil.displayName(uri)
    }

    private fun extractUrl(intent: Intent?): String? {
        val raw = when {
            intent?.action == "com.mengpaw.action.OPEN_URL" -> intent.getStringExtra("url")
            intent?.dataString != null -> intent.dataString
            else -> null
        }
        // SECURITY: Only allow http/https schemes — block javascript:, file:, content:, etc.
        return if (raw != null && (raw.startsWith("http://") || raw.startsWith("https://"))) raw else null
    }

    /** Back key: 全屏视频优先退出, 其次交给 Compose 的标签/历史逻辑。 */
    override fun onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen()
            return
        }
        onSystemBack?.invoke() ?: super.onBackPressed()
    }

    /**
     * v0.10.2: 与 [onTrimMemory] 的 onPause 配对恢复。
     * 旧实现只 onPause 从不 onResume — 一次后台/内存压力后 WebView 可能长期停留暂停态,
     * 页面继续加载却不再渲染 (用户视角: 转到之后「没反应」)。
     */
    override fun onResume() {
        super.onResume()
        webViewMapRef.values.forEach { wv ->
            try { wv.onResume() } catch (_: Exception) {}
        }
    }

    /** Mutable reference to Compose's webViewMap, synced via SideEffect. */
    internal var webViewMapRef: MutableMap<Int, WebView> = mutableMapOf()
    /** System back key callback set by Compose. */
    internal var onSystemBack: (() -> Unit)? = null
    /** OPEN_URL 热路径回调 (重复打开时由 onNewIntent 触发)。 */
    internal var onOpenUrl: ((String?) -> Unit)? = null
    /** OPEN_MD 提炼回传回调: (title, url, md) → 弹 Markdown 预览。 */
    internal var onOpenMd: ((String, String, String) -> Unit)? = null
    /** 本地 .pdf 打开回调: (源 uri 串, 显示名) → 弹 PDF 预览。 */
    internal var onOpenPdf: ((String, String) -> Unit)? = null

    // ── P1 fix: BuiltinBrowserPlugin 接线 (此前零实例化, browser.* 命令不可达);
    //    v0.8.0 半自动武器: page.* + browser.* 43 条, 经 am 桥暴露 (9880 桥已退役) ──

    /** BrowserApp (Compose) 暴露的标签页状态桥 — 命令经它操作真实 UI 状态。 */
    interface BrowserStateBridge {
        fun activeWebView(): WebView?
        fun currentTabs(): List<TabState>
        fun currentActiveTabId(): Int
        fun switchTab(id: Int)
        /** 打开/切换标签；返回 null=已受理，非 null=失败原因（v0.10.3: 不再无声失败） */
        fun openTab(id: Int, url: String): String?
        fun closeTab(id: Int)
    }

    /** 由 BrowserApp 的 SideEffect 每次重组赋值 (Compose state 可变)。 */
    @Volatile
    internal var browserState: BrowserStateBridge? = null

    companion object {
        /** 站点运行时权限请求码（摄像头/麦克风/定位）。 */
        private const val REQ_SITE_PERMISSIONS = 0x4D50

        /** 主线程执行（命令线程调用状态桥时必须切回主线程 — WebView/UI 只能在主线程碰）。 */
        private fun onMain(block: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }

        /** 标签页状态桥工厂 — BrowserApp (Compose) 注入 state 读写闭包。 */
        internal fun createStateBridge(
            tabs: () -> List<TabState>,
            setTabs: (List<TabState>) -> Unit,
            activeTabId: () -> Int,
            setActiveTabId: (Int) -> Unit,
            setIsColdStart: (Boolean) -> Unit,
            webViewMap: MutableMap<Int, WebView>,
            maxTabs: Int,
            /** v0.10.3: WebView 尚未创建时把导航登记为待办（与地址栏同一通道） */
            setPendingLoad: (com.mengpaw.browser.data.PendingLoad) -> Unit = {},
            nextSeq: () -> Long = { 0L }
        ): BrowserStateBridge = object : BrowserStateBridge {
            override fun activeWebView(): WebView? = webViewMap[activeTabId()]
            override fun currentTabs(): List<TabState> = tabs()
            override fun currentActiveTabId(): Int = activeTabId()
            override fun switchTab(id: Int) {
                if (tabs().none { it.id == id }) return
                // v0.10.3: 命令线程 → 主线程（旧实现直接写 Compose/碰 WebView）
                onMain { setActiveTabId(id) }
            }
            override fun openTab(id: Int, url: String): String? {
                val current = tabs()
                val existing = current.any { it.id == id }
                if (!existing && current.size >= maxTabs) return "标签页已达上限 ($maxTabs)，请先关闭部分标签页"
                onMain {
                    val cur = tabs()
                    if (cur.any { it.id == id }) {
                        setTabs(cur.map { if (it.id == id) it.copy(url = url) else it })
                        setActiveTabId(id)
                        setIsColdStart(false)
                        val wv = webViewMap[id]
                        if (wv != null) {
                            try { wv.loadUrl(url) } catch (_: Exception) {
                                setPendingLoad(com.mengpaw.browser.data.PendingLoad(id, url, nextSeq()))
                            }
                        } else {
                            setPendingLoad(com.mengpaw.browser.data.PendingLoad(id, url, nextSeq()))
                        }
                    } else {
                        val newId = (cur.maxOfOrNull { it.id } ?: -1) + 1
                        setTabs(cur + TabState(id = newId, url = url))
                        setActiveTabId(newId)
                        setIsColdStart(false)
                        setPendingLoad(com.mengpaw.browser.data.PendingLoad(newId, url, nextSeq()))
                    }
                }
                return null
            }
            override fun closeTab(id: Int) {
                if (tabs().size <= 1) return  // 保持至少一个标签
                onMain {
                    val wv = webViewMap.remove(id)
                    if (wv != null) {
                        // 先脱离视图树再 destroy (P1 fix — attached destroy 风险)
                        try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                    }
                    setTabs(tabs().filter { it.id != id })
                    setActiveTabId(tabs().firstOrNull()?.id ?: 0)
                }
            }
        }
    }

    /** 内置浏览器命令插件 — 经 am 桥 (RunCommandService) 暴露给 Agent。 */
    private val builtinBrowserPlugin: com.mengpaw.browser.plugin.BuiltinBrowserPlugin by lazy {
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin(
            webViewProvider = { browserState?.activeWebView() },
            tabInfoProvider = {
                val bs = browserState ?: return@BuiltinBrowserPlugin emptyList()
                bs.currentTabs().map { t ->
                    com.mengpaw.browser.plugin.BrowserTab(
                        id = t.id, url = t.url, title = t.title,
                        isLoading = t.isLoading, isActive = t.id == bs.currentActiveTabId()
                    )
                }
            },
            tabSwitcher = { id -> browserState?.switchTab(id) },
            tabOpener = { id, url -> browserState?.openTab(id, url) ?: "浏览器未就绪（请先打开 MP 浏览器）" },
            tabCloser = { id -> browserState?.closeTab(id) }
        )
    }

    /** 安全销毁 WebView: 先脱离视图树再 destroy (P1 fix — attached 时 destroy 有崩溃风险)。 */
    private fun destroyWebViewSafe(wv: WebView?) {
        if (wv == null) return
        try {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        } catch (_: Exception) {}
        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // 复位挂起的站点回调（审查 P1-4）: 不收尾会让站点 getUserMedia/定位 Promise 永久 pending，
        // 且回调闭包持有 WebView 侧的 PermissionRequest/ValueCallback。
        try { pendingPermissionCallback?.invoke(false) } catch (_: Exception) {}
        pendingPermissionCallback = null
        try { pendingFileCallback?.onReceiveValue(null) } catch (_: Exception) {}
        pendingFileCallback = null
        super.onDestroy()
        // 全屏视频宿主先撤（避免持有已销毁 WebView 的父容器）
        try { exitFullscreen() } catch (_: Exception) {}
        // CRITICAL: Destroy all WebViews to free native renderer memory
        webViewMapRef.values.forEach { destroyWebViewSafe(it) }
        webViewMapRef.clear()
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) { }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // Pause all non-visible WebView rendering
                webViewMapRef.values.forEach { wv ->
                    try { wv.onPause() } catch (_: Exception) {}
                }
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // P1 fix: 不再 destroy — 所有 WebView 仍在 Compose 树 (AndroidView) 渲染中,
                // destroy 后 UI 继续引用已销毁实例必崩, 且旧实现 destroy 后残留 map 引用。
                // 改暂停全部 + 清缓存 (渲染内存的主要来源)。
                webViewMapRef.values.forEach { wv ->
                    try { wv.onPause() } catch (_: Exception) {}
                }
                try { webViewMapRef.values.firstOrNull()?.clearCache(true) } catch (_: Exception) {}
            }
        }
    }
}

/** 拉取站内 .md URL 内容 — top-level 使 BrowserApp (类外) 可访问。
 *  HttpURLConnection 一次性请求, 500K 截断对齐 readMdUri; 失败返回 null。 */
internal fun fetchUrlTextTop(url: String): String? {
    return try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            // P2 fix: UA 版本号不再硬编码 "0.7" — 随 BuildConfig.VERSION_NAME 自动同步
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPawBrowser/${BuildConfig.VERSION_NAME}")
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val charset = (conn.contentType ?: "")
                .substringAfter("charset=", "").trim().ifBlank { "UTF-8" }
            val text = conn.inputStream.bufferedReader(java.nio.charset.Charset.forName(charset))
                .use { it.readText() }
            text.take(500_000)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }
}

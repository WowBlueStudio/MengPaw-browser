# CHANGELOG — MengPaw Browser

> 浏览器独立仓库版本记录。版本单点: `gradle.properties` 的 `mengpaw.browser.version`。
> 许可: AGPL-3.0-or-later OR LicenseRef-Commercial。

## 未发布 (Unreleased) — 累计修改

> 按用户指令：**累计改动，不急着出 APK**。以下条目已实现 + 单测通过 + 已提交，未构建发行包、未发版。
> 当前开发版本号 `0.10.5`（`versionCode` 派生 1005）；作为**测试包**装到平板验证用，未打 tag、未推送。

### 新增

- **形态自适应 UA：平板请求桌面版网页，手机请求移动版网页**
  - Android WebView 默认 UA 带 `Mobile` 标记 ⇒ 平板屏再宽，站点也给移动版（窄栏、触屏样式）。
    现在平板改用桌面 UA（Windows 标识 + **与当前 WebView 引擎同主版本**，避免
    「UA 说 Chrome 120、引擎是 Chrome 108」触发站点能力探测异常）
  - 手机保持系统默认 UA（`WebSettings.getDefaultUserAgent`，含 `Mobile`）
  - **判定口径是设备类别不是当前宽度**：用 `smallestScreenWidthDp >= 600`（Android 官方平板判据），
    不是 `screenWidthDp >= 600` —— 否则**手机横屏**（宽度也 ≥600dp）会被误判成平板而请求桌面版
  - 幂等应用：WebView 创建时 + `AndroidView.update` 都调用 `applyUserAgent`，旋转/折叠屏展开/
    分屏改变形态后自动纠正；**已加载页面需刷新**才按新 UA 重新请求
  - 纯函数 `web/UserAgent.kt` + `web/UserAgentTest.kt` 4 条守护
    （平板不含 Mobile / 手机不改写 UA / 主版本跟随引擎 / 两形态互不污染）

### 修复（站点兼容性 — 用户报障「trekbikes.com 顶栏分类菜单点不开 / 100% 高容器异常 / 浮层弹窗全部没加载」）

- **「打开 App」推广条拦截列表大幅收窄（很可能是浮层消失的直接原因）**
  `HIDE_APP_BANNER_JS` 旧列表含 `[class*=floatBtn]`、`[class*=bottom-bar]`、`[class*=bottomBar]`、
  `[class*=openIn]`、`[class*=float-app]`、`[class*=native-app]`、`[class*=mobile-app]`、
  `[class*=goToApp]`、`[class*=appLink]` 等**通用类名子串**，并一律 `display:none!important`。
  正常站点的**正当浮层**（浮动购物车/客服/底部操作条/弹窗内层）大量使用这些类名 ⇒ 被我们隐藏。
  现在只保留语义明确指向 App 推广的选择器。
- **广告拦截不再拦功能性查询参数**：查询规则去掉 `ref=` 与 `sponsored`（站点内部跳转/接口
  `?ref=nav`、`?ref=header` 极常见），只保留纯追踪参数（`utm_*`/`gclid`/`fbclid`/`msclkid`/`yclid`/`adid`）。
  被误拦会让站点的导航/浮层数据取不到，表现为「分类菜单点不开、浮层不出现」这类局部 JS 初始化失败。
- **站点 `window.open` / `target=_blank` 弹层现在有承接**：此前未开
  `setSupportMultipleWindows(true)` ⇒ 站点用 window.open 打开的浮层/快捷查看/登录窗**静默消失**。
  现在用一次性探针 WebView 取到真实 URL 后**开新标签**（与 Chrome 行为一致）；
  达标签上限时给出 Toast 而不是静默丢弃。

### 新增（兼容性能力补齐 — 对齐 Edge/Chrome）

- **文件上传**：实现 `WebChromeClient.onShowFileChooser` + `ActivityResultContracts` 启动器
  （`BrowserActivity.launchFileChooser`）—— 此前站点任何"上传图片/附件"按钮点了毫无反应。
- **摄像头 / 麦克风**：实现 `onPermissionRequest`（`getUserMedia`）→ 按需申请
  `CAMERA`/`RECORD_AUDIO` 运行时权限后再 `grant`；非摄录类资源（MIDI 等）直接授予。
- **定位**：实现 `onGeolocationPermissionsShowPrompt` → 申请 `ACCESS_FINE/COARSE_LOCATION`
  后按结果 `invoke(origin, granted, retain=false)`（不长期记住，避免静默追踪）。
- **视频全屏**：实现 `onShowCustomView` / `onHideCustomView` + `BrowserActivity.enterFullscreen/
  exitFullscreen`（黑底全屏容器 + 沉浸式 + 返回键优先退出；`onDestroy` 清理）—— 此前全屏按钮无效。
- **文件下载**：新增 `setDownloadListener` → 系统 `DownloadManager` 落到公共下载目录
  （带 Cookie/UA、通知栏进度、Toast 反馈），并顺带清除加载态 —— 此前"下载尺寸表/说明书"
  类按钮点了没反应，还会把加载指示器留在转圈。
- **站点形态开关（桌面版/移动版）**：设置里新增三选一（自动 / 始终桌面版 / 始终移动版，
  `data/UaMode.kt` + `UaModeTest`）。默认"自动"= 平板桌面版、手机移动版；站点不适配时
  用户可一键覆盖、刷新生效，不必等发版。

### 修复（当前正在处理：Trek 首页 100vh 首屏挤成"窄条"）

- 已定位根因方向（社区已知类问题）：**页面在 WebView 尚未布局（尺寸 0×0）时就开始加载**，
  站点按 0 高度算出整屏 hero 高度并缓存；另有**非激活标签以 `height(0.dp)` 排布却仍加载 URL**，
  同样在 0 高度下完成首屏计算。修复项（`loadWhenLaidOut` + 非激活标签懒加载）由并行子会话落地中，
  落地后在此补记。


- **站点 JS 报错可见**：注入 `error` / `unhandledrejection` 钩子（幂等，`onPageStarted` +
  `onPageFinished` 双注入），并经 `WebChromeClient.onConsoleMessage` 把 WARNING 以上打到
  **`MpWebConsole`** tag ⇒ 现场可直接 `adb logcat -s MpWebConsole` 看到站点报了什么错，
  不必再靠猜（多窗口探针的 URL、错误行号都在里面）。

## v0.10.4 (2026-10-13) — 载入反馈：顶栏「点击即转」的旋转指示器

### 修复 / 新增

- **顶栏载入指示器（`CircularProgressIndicator`，品牌色）**：只要当前标签在载入就转，
  用户不必等到失败页才发现「点了没反应」。
  - **手机布局**：载入中**临时代替**标签菜单按钮（同一位置、同 40dp 占位不跳位）——
    把最显眼的左侧位置让给指示器；载入结束立即恢复标签按钮
  - **宽屏布局**：指示器出现在常驻导航按钮（主页/后退/前进/刷新）左侧，只有载入时占位
- **把加载态提前到"点击那一帧"**（旧版要等 `onPageStarted`／网络回包才亮，慢站上肉眼几乎没有反馈）：
  - 地址栏回车/点 →：`navigate()` 同帧置 `isLoading=true`（v0.10.2 起已如此）
  - **页面内点击链接**：`shouldOverrideUrlLoading` 放行前即置位（本次新增）
  - **下拉刷新**：先置位再 `reload()`（本次新增）
  - **顶栏 刷新/后退/前进** 与快捷键 `Ctrl+R`/`F5`/`Alt+←→`：新增 `startLoading()` 同帧置位（本次新增）
- 线性进度条保留（它显示真实进度，圆环负责"是否在载入"的即时判断）。
- 加载态收口沿用 v0.10.3：主帧失败 / SSL 错误 / `.md`·`.pdf` 拦截分支都会清除，不会留下转不完的圈。

### 发行

- APK: `mengpaw-browser-v0.10.4-release.apk`（versionCode 派生 = **1004**，签名 CN=MengPaw, OU=Studio, O=WowBlue）
- 测试: `:mengpaw-browser:testDebugUnitTest` 69 用例全绿（无新增用例；改动为 UI 与状态置位）

## v0.10.3 (2026-10-13) — 三层闭环+九维审计全量修复（命令面可达/数据完整性/落盘校验）

### 修复 (P0 — Agent 完全不可达/数据丢失/崩溃)

- **21 条 `browser.*` 命令对 Agent 完全不可达（双向死路）**
  - 注册表用短名（`tabs`/`tab.open`/`cookies.set`…），手册与 am 桥用 Agent 形态（`browser.tabs`…）；
    写完整名 → 过白名单但查不到注册键（「未知浏览器命令」）；写短名 → 被前缀白名单拒绝。
    `page.*` 22 条不受影响，故一直未被发现。
  - 新增 `plugin/CommandNames.kt`：`resolveCommandKey` 两种形态都接受（精确优先，其次剥 `browser.`）；
    失败提示直接列出全部可用命令名。新增 **`CommandSurfaceTest`** 把「文档教的命令名」
    与「注册表实际命令名」钉死（含幽灵引用扫描 + 手册覆盖率断言）——四源同步从此有守护。
- **收藏夹/标签会话用分隔符拼接 → 静默数据损坏**：书签以 `,`、标签会话以 `|` 拼接存储，
  URL 含分隔符即被拆成垃圾项，且星标/取消收藏对该 URL 永久失效。改为 JSON 编码
  （`StringListCodec`，兼容旧数据读取）。
- **`browser.tab.open` / `browser.preload` 谎报成功**：`openTab` 返回 Unit，未真正打开
  （无 WebView/达上限）也回「正在打开」。现在返回失败原因，命令层如实转失败；
  Agent 路径同样走 `PendingLoad` 待办通道。
- **返回键兜底分支遍历中销毁 WebView**（先摘快照 → 清空 map → 再 destroy）；
  `loadInTab` 对已销毁 WebView 的 `loadUrl` 加兜底回落到待办导航。
- **标签关闭回调空表 `first()`**（桌面标签栏路径）→ 空表兜底 + `firstOrNull`。

### 修复 (P1 — 静默失败/静默丢数据/状态不一致)

- **截图落盘不再无条件成功**: `BrowserBridge.screenshot()` 写盘后校验文件存在且非空
  （`verified:true`），失败如实 `ok:false`；`page.screenshot` 不再把 `ok:false` 当成功回传。
- **截图/元素截图改为主线程执行**（旧实现在命令线程读 `webView.width` 并 `draw()`，属未定义行为）；
  `page.goto` 校验 `post` 返回值（WebView 已销毁时不再空等超时，直接报 `webview detached`）。
- **`browser.wait.nav` 不再只 sleep**: 改为真实等待加载状态（`waitForLoad`），超时如实失败。
- **`browser.dialog.accept/dismiss` 诚实化**: 旧实现注入 `window.__mpDialogCb` —— 全仓库从未定义，
  是恒成功的空操作。现如实返回「不支持自动应答」并给替代方案（`page.eval` 预覆盖）。
- **`page.screenshot --view` 落地**（手册与排障流程一直在教，实现里却没有）；
  `--max-height` 生效（此前被静默忽略，只认 pref）。
- **输出落盘路径穿越修复**: `writeOutput` 由字符串前缀改为 `canonicalFile` 路径包含判断，
  `/MengPaw/../Download/x` 之类不再逃逸；落盘失败不再静默（Log 留痕）。
- **Agent 桥切回主线程**: `switchTab/openTab/closeTab` 经 `Handler(mainLooper)` 执行
  （命令跑在后台线程，直接写 Compose 状态/碰 WebView 属越界）；`BuiltinBrowserPlugin.maxTabs`
  与 UI 同源（此前 tab 命令硬编码 0..3，第 5 个标签对 Agent 不可见）。
- **`browser.tab.all` 不再张冠李戴**: 切到目标标签后等其加载完（最多 8s）再读**该标签自己的**
  WebView；标题改 JSON 解析（旧实现用正则抠引号）。
- **JS 转义链闭合**: `escapeJs` 补 U+2028/U+2029（JS 行终止符）；`cookies()` 返回体改
  `JSONObject` 构造（Cookie 值含引号会让 Agent 收到非法 JSON）。
- **广告拦截不再误伤子资源**: 域名/路径特征只匹配 `host+path`（不含 query/fragment），
  `utm_*` 特征只匹配 query。
- **历史记录**: 持久化移出主线程（不再每次导航在主线程重写 500 条单 key）；
  「清空全部」后列表立即刷新（旧实现关窗重开仍显示旧条目，用户视角「清空没用」）。
- **暗色模式关闭生效**: 显式移除已注入的 `#mp-dark` 样式（旧实现只置 prefs，同页残留暗色）。
- **非激活标签挂起**: 切走的标签 `onPause()`（此前只 alpha/height 隐藏，后台标签仍在渲染与播放）。
- **PDF 预览缓存清理**（每次预览写 cacheDir 且从不删除）+ 所有分支 `disconnect()`。
- **图片下载**: 巨图按 `inSampleSize` 降采样（防 OOM）、异常路径 `recycle()`、
  同名自动加后缀（不再静默覆盖）、按原格式选 PNG/JPEG（半透明不再变黑）。
- **网络错误提示可执行**: DNS 失败/超时/重置/证书错误都给出下一步（换协议重试、查网络、
  用系统浏览器验证），不再只陈述原因。
- **明文 http 站点成功/失败状态收口**: `.md`/`.pdf` 拦截分支不再留下永久加载态。
- **`versionCode` 由 `versionName` 派生**（`major*10000+minor*100+patch`）：
  消灭「手改字面量漏改 → 客户端识别不出更新」这一类事故（AGENTS 自述的历史坑）。

### 修复 (P1 — 代码质量/红线)

- **`BrowserBridge.kt` 496 → 391 行**（400 行红线）：JS 求值管道拆 `JsEvaluator`、
  加载等待拆 `LoadWaits`、会话数据拆 `SessionOps`；类注释纠正（`addJavascriptInterface`
  注册已于 v0.10.2 移除，本类只由 Kotlin 侧持有 —— 旧注释会诱导恢复暴露面）。

### 真机自测待做 (Agent 未验证 — 无设备连接, 需用户确认)

- `browser.*` 21 条命令经 am 桥可达（例: `browser.tabs`、`browser.tab.open 0 https://example.com`）
- `page.screenshot --view` 出视口图；`page.screenshot` 返回体含 `verified:true`
- `browser.wait.nav 5000` 在慢站上真实等待；`browser.dialog.accept` 返回「不支持自动应答」
- 收藏含 `,` 的链接后重启：星标仍在、点击可取消
- 关闭暗色模式立即恢复亮色；切走标签后后台不再播放音频
- 清空历史后弹窗列表立即变空

### 发行

- APK: `mengpaw-browser-v0.10.3-release.apk` — versionCode **1003**（由 versionName 派生：
  `0.10.3 → 0*10000 + 10*100 + 3`），签名 `CN=MengPaw, OU=Studio, O=WowBlue`
  （`apksigner --print-certs` + `aapt2 dump badging` 双复核；1003 > 上一版字面量 18，更新链路可识别）
- plugins.json: 无变更（浏览器独立仓库不含插件市场）
- 测试: `:mengpaw-browser:testDebugUnitTest` **69 用例全绿**（32 → 69）
  - SmartNavigateTest 19 / AdBlockerTest 13 / PdfUtilTest 11 / DownloadUtilTest 11 /
    StringListCodecTest 11 / **CommandSurfaceTest 4（新增守护）**
- 审计口径: 三层二十一问（认知/逻辑/基础设施各 1 代理）+ 九维审查（A/B/C/D 四分区）+
  P0/P1 对抗验证；本轮修复 in-repo 缺口，跨仓项另列（见下）
- 跨仓待办（本会话不改主仓库，留给 Shell 会话）：
  ① `mengpaw-kernel/.../agent/AgentCliDocTables.kt:36-41` 仍列已退役的 `browser.batch`/`browser.q`，
     且该文件不在 `PromptGhostReferenceTest` 的扫描清单内；
  ② `plugins/plugin-skill` 的 5 份浏览器技能文档仍是旧版（手册同步需随 Shell 侧进行）；
  ③ `kernel/security/CommandMonitor.kt:264` 的 `takeWhile { !it.startsWith("--") }` 只在
     **未加引号**传参时截断 payload（文档推荐的引号形态不受影响）—— 建议加注释/测试固化该约定；
  ④ 已退役 9880 桥/MCP 开放模式在 Shell 侧文档的清理（v0.9.0 起登记，尚未执行）
- 遗留（已评估、本轮不改，理由随附）：AndroidManifest 里 exported `BrowserActivity` 的
  `file://` 打开面（Android 7+ 发送方会被 FileUriExposedException 拦住，且文件内容不经通道回传，
  风险低于审计判断；收窄 intent-filter 会破坏「文件管理器打开 .md/.pdf」的既有入口）；
  `AndroidView.update` 内写 Compose 状态（待办导航消费点的取舍，已加 seq 去重）；
  无 androidTest/仪器测试（本机无设备）。

## v0.10.2 (2026-10-13) — 地址栏转到修复 + 网站打不开修复 + 控件/按键普查

### 修复 (P0 — 用户报障)

- **地址栏「转到」点了没反应 / 一段时间后跳到「页面加载失败」**
  - 根因 1 (**获焦不全选**): 点进地址栏只落光标, 新输入的网址被拼进原 URL 里
    (如 `https://old.site/x` + `github.com`), 拼出的怪地址必然 DNS 解析失败 → 延迟后落到错误页。
    现按浏览器惯例**获焦即全选**, 输入直接覆盖旧地址
  - 根因 2 (**导航静默丢失**): `navigate()` 只做 `webViewMap[id]?.loadUrl()`, WebView 尚未创建
    (冷启动首帧/刚切标签) 时该调用是空操作, 地址栏状态却已更新 → 页面毫无反应。
    新增 `PendingLoad` 待办导航: 由内容区在 WebView 就绪的同一帧消费, 且构造期不重复加载
  - 根因 3 (**编辑中被页面 URL 覆盖**): `LaunchedEffect(activeTab.url)` 无条件回写输入框,
    页面重定向/加载完成会把用户正在输入的内容抹掉 — 现在编辑态(有焦点)期间不回写
  - 提交路径统一: → 按钮 / 软键盘 搜索·前往·完成 / 物理回车 都走同一个 `submit()`;
    提交后清焦点收键盘; 空输入给提示不再静默; → 按钮在空输入时禁用
- **部分网站打不开 (如 `http://47.119.135.155/home`)**
  - 根因: targetSdk 35 下 `usesCleartextTraffic` 默认 false, WebView 对**所有明文 http 站点**
    直接返回 `net::ERR_CLEARTEXT_NOT_PERMITTED`。已显式置 true (Chrome 同做法)
- **裸 IP / 中文输入法域名输不进去**
  - `smartNavigate` 归一化输入: 全角 ASCII 与 `。．·` 折算半角、去零宽字符 — 中文输入法把 `.`
    打成 `。` 时 `github。com` 此前被判成关键词走搜索, 只能靠搜索绕到目标站
  - 新增裸地址判定: IPv4/IPv6 字面量、`localhost`、单标签内网主机 + 端口 → 补 `http://`
    (裸 IP 的 https 需证书含 IP SAN, 现实罕见); 域名形态 (含 FQDN 尾点/端口/中文路径) → `https://`
  - 大小写协议 (`HTTP://`) 与协议相对 (`//host`) 均正确识别
- **错误页可诊断可恢复**: 主帧失败时展示错误码 + 中文解释 (解析失败/超时/连接重置/证书无效)
  + **重试按钮**; 主帧失败同时停止加载态 (旧实现进度条常驻); SSL 证书错误也改为换页提示
  (旧实现只弹 Toast, 旧页面残留 → 看着像「点了没反应」)
- **广告拦截误杀整页**: 拦截规则此前对**主帧**同样生效, 主帧 URL 命中广告特征 (如 `/ad/` 路径段、
  `utm_*` 参数) 时整页被替换成空响应 → 表现为「网站打不开」。现仅作用于子资源
- **WebView 暂停后不再恢复**: `onTrimMemory` 对所有 WebView `onPause()` 却从不 `onResume()`,
  后台/内存压力后页面可能停止渲染。补 `onResume` (Activity 恢复 + 激活标签切换时)
- **站点按钮点了没反应 (非 http 协议链接)**: `tel/sms/mailto/intent/market/weixin/alipays/...`
  此前除 4 种外一律拦截, 现在交给系统处理 (危险协议 `javascript:/data:/blob:/content:` 仍拦截)

### 修复 (控件普查 — 「确保每个按钮都有用」)

- 删除 **「静音标签」**(DesktopTabBar 标签菜单): 处理器只关菜单, 全仓库无静音实现
- 删除 **「快速点击」开关** / **「截图质量」滑杆**(智能体协同设置): 两个 pref 全仓库无读取点
  (截图固定 PNG, quality 参数被忽略) — 典型假控件
- 「自动注入桥接」「广告拦截」改为**动态读取**: 此前在 WebView 构造期被捕获, 切换设置对已建
  标签页无效
- **图片保存**修复: 旧实现用弹窗自身作用域启动下载, 同帧 `onDismiss()` 取消协程 → 静默失败却
  提示「已保存」; 现在用长生命周期作用域, 落盘公共相册 `Pictures/MengPaw` (图库可见),
  成败给出真实反馈
- **收藏夹改为 state**: 增删即时刷新顶栏星标、收藏列表与新标签页快捷入口 (旧实现直读
  SharedPreferences 普通 getter, 点了看不出变化); 顶栏菜单新增「收藏/取消收藏当前页」
- **单标签时不再显示关闭 X**(TabChip): 旧实现 `onClose ?: onClick` 让「关闭」变成「切换标签」
- 「推送给智能体」失败不再静默 (Shell 未安装时 Toast), 与 `sendToAgent` 行为对齐
- 「清除所有密码」仅在 API < 33 渲染 (Android 13 起框架已移除该能力, 旧实现只弹 Toast)
- 页面级菜单 (翻译/查找/阅读) 的可用性只看「有没有页面」, 不再因标题为空而禁用
- 设置页新增**浏览器版本**显示 (报障时不再需要猜版本)

### 新增 — 键盘/快捷键 (此前全仓库无任何按键处理)

- `Ctrl/⌘+L` 聚焦地址栏 / `Ctrl/⌘+R`、`F5` 刷新 / `Alt+←→` 后退·前进 /
  `Ctrl/⌘+T` 新标签 / `Ctrl/⌘+W` 关标签 / `Ctrl/⌘+F` 页面查找 / `Ctrl/⌘+D` 收藏 /
  `Esc` 逐层关闭 (查找 → 阅读 → 翻译 → 弹窗 → 收起地址栏)
- 返回键/Esc 语义统一: 查找栏是内联布局而非 Dialog, 旧实现按返回键会**翻页**而查找栏留在原地
- 手机点地址栏后自动聚焦 (省一次点击); 地址栏键盘不再自动大写/自动纠错; IME 边距
  (`imePadding`) 避免软键盘遮挡底部查找栏

### 发行

- APK: `mengpaw-browser-v0.10.2-release.apk` (versionCode 18, 签名 CN=MengPaw, OU=Studio, O=WowBlue;
  `apksigner` 与 `aapt2 dump badging` 双复核: versionName 0.10.2 / targetSdk 35 / 明文流量已放行)
- plugins.json: 无变更 (浏览器独立仓库不含插件市场)
- 测试: `:mengpaw-browser:testDebugUnitTest` 32 用例全绿
  (AdBlockerTest 8 + SmartNavigateTest 19 + PdfUtilTest 5) — SmartNavigate 13→19
- 构建: `.\gradlew.bat :mengpaw-browser:assembleRelease --build-cache --console=plain` (未 clean)
- 双平台 Release 同批创建并附 APK (GitHub + Gitee)

### 真机自测待做 (Agent 未验证 — 无设备连接, 需用户确认)

- 地址栏: 获焦全选 → 输入 `github.com` → 回车/点 → 直达; 中文输入法打成 `github。com` 也直达
- 明文站点: `http://47.119.135.155/home` 能打开 (旧版本必报 ERR_CLEARTEXT_NOT_PERMITTED)
- 失败页: 断网或错误域名 → 显示错误码 + 中文解释 + 「重试」按钮, 进度条停止
- 快捷键 (平板/外接键盘): `Ctrl+L/R/T/W/F/D`、`Alt+←/→`、`F5`、`Esc`
- 控件: 收藏/取消收藏即时生效、图片保存落到 `Pictures/MengPaw`、单标签无关闭 X、
  设置页可见浏览器版本

## v0.10.1 (2026-09-10) — 项目定位纠正: 与 MengPaw Shell 脱钩

### 变更 (文档, 无代码改动)

- **纠正定位表述**: 本项目是**中性的独立项目**, 不隶属于 MengPaw Shell —— 由 MengPaw 主仓库
  按 APK 产物拆分而来, 现已独立演进; Shell 把 Browser 当工具使用(含主动检查/安装其更新)属
  **Shell 自身的集成行为**, 与本项目独立演进不矛盾, 第三方 Shell 类软件同样可按命令手册适配
- **明确耦合边界 (仅两项)**: ① 命令手册 `docs/MengPaw_Browser_skills.md` 需同步给 Shell 侧;
  ② Android 开发经验共同沉淀于主仓库 `docs/lessons.md`
- **已脱钩**: 版本线 / 发版 / 更新链路(原「由 Shell `update` 插件捎带管理」表述已删除) /
  路线图 / 文档归属
- 技术表述统一: 「仅同签名 Shell 可调」→「仅同签名应用可调」(am 桥仍为运行时接口, 未退役)
- 涉及文件: `README.md` / `mengpaw-browser/docs/MengPaw.Browser 开发文档.md` /
  `mengpaw-browser/docs/MengPaw_Browser_skills.md`

### 发行

- APK: `mengpaw-browser-v0.10.1-release.apk` (versionCode 17, 签名 CN=MengPaw, OU=Studio, O=WowBlue)
  注意: 本轮为**纯文档变更**, APK 功能代码与 v0.10.0 完全一致, 仅版本元数据变化
- plugins.json: 无变更 (浏览器独立仓库不含插件市场)
- 测试: `:mengpaw-browser:testDebugUnitTest` 26 用例全绿
  (AdBlockerTest 8 + SmartNavigateTest 13 + PdfUtilTest 5)

## v0.10.0 (2026-09-10) — PDF 预览 (pdf.js)

### 新增

- **PDF 预览** (对齐 Markdown 预览形态的近全屏 WebView 对话框):
  - 支持**放大/缩小**(工具栏 −/＋/适应宽度, 40%–400%)与**文字选取**(pdf.js 文本层, 按住拖动选中/复制)
  - 支持**网络文件**(`.pdf` URL 导航/链接点击被拦截并预览)与**本地文件**
    (file/content `.pdf` VIEW intent — 文件管理器/SAF/FileProvider 打开)
  - 引擎 `assets/pdf_viewer`: pdf.js 2.16.105 + 中文字形 CMap(packed, 168 个) + viewer 模板/css/js
  - 数据链路: 检测(`isPdfUrl`/`PdfUtil`)→ 取数(HTTP/content/file, ≤30MB)→ 写缓存 →
    base64 内联渲染; 分页懒加载窗口渲染 + 页面回收
- 主要文件: `util/PdfUtil.kt` / `web/PdfViewerHtml.kt` / `web/PdfViewerWebView.kt` /
  `ui/BrowserPdfViewerDialog.kt` + `assets/pdf_viewer/*`

### 修复/清理

- 删除 `mengpaw-browser/extension-manifest.json` — 早期「浏览器作为扩展」时代的残留清单
  (内容是 Chromium 扩展、maxCoreVersion 0.8.1), 两仓库均无引用

### 注意

- 受密码保护的 PDF 暂不支持输入密码 (提示后关闭)
- 真机自测待做 (pdf.js 在 WebView 的渲染/文字层/中文 CMap 需人工验证)

### 发行

- APK: `mengpaw-browser-v0.10.0-release.apk` (versionCode 16, 签名 CN=MengPaw, OU=Studio, O=WowBlue)
- plugins.json: 无变更 (浏览器独立仓库不含插件市场)
- 测试: `:mengpaw-browser:testDebugUnitTest` 26 用例全绿
  (AdBlockerTest 8 + SmartNavigateTest 13 + PdfUtilTest 5)

## v0.9.0 (2026-08-29) — am 桥单通道: 退役 9880 桥与开放模式

### 变更

- **退役 9880 HTTP 桥与 MCP 开放模式**(决策 #7): 删除 `McpHttpServer`/`McpAuthPolicy`/
  `BrowserMcpTools`/token 注入/「开放 MCP 控制」设置开关与 UI/`MCP_BRIDGE` 权限;
  第三方 Agent 接入取消, 浏览器仅接受同签名 Shell 经 am 桥控制
- **移除 `batch`/`q` 命令**(决策 #4 去重收尾): `browser.*` 23→21 条,
  命令面合计 43 条(`page.*` 22 + `browser.*` 21)
- 文档同步: 开发文档 / skills 手册 / autopilot 计划对齐 am 桥单通道

### 发行

- APK: `mengpaw-browser-v0.9.0-release.apk`(versionCode 15, 签名 CN=MengPaw, OU=Studio, O=WowBlue)
- plugins.json: 无变更(浏览器独立仓库不含插件市场)
- 测试: `:mengpaw-browser:testDebugUnitTest` 21 用例全绿(AdBlockerTest 8 + SmartNavigateTest 13)

## v0.8.1 (2026-08-17) — MCP 开放模式

- 新增 MCP 开放模式: 第三方 Agent 经 9880 免认证控制(Playwright 式, 仅回环; 已于 v0.9.0 退役)

## v0.8.0 (2026-08-11) — 半自动武器

- 新增 `page.*` Playwright 语义命令面 22 条 + `page.load` 半自动合体 + am 桥(`RunCommandService`)
  + 超长页分段坐标 + 公共目录截图

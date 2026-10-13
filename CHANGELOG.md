# CHANGELOG — MengPaw Browser

> 浏览器独立仓库版本记录。版本单点: `gradle.properties` 的 `mengpaw.browser.version`。
> 许可: AGPL-3.0-or-later OR LicenseRef-Commercial。

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

# MengPaw.Browser 开发文档

> 独立 APK (Android 浏览器应用), 内置 Playwright 语义命令面, 供 AI Agent 半自动控制。
> 文档版本: v0.9.0 · 2026-08-29 · 许可: AGPL-3.0-or-later OR LicenseRef-Commercial
> 本文档分两部分: **第一部分**引导 Shell Agent 经 am 桥控制浏览器并开始操作;
> **第二部分**面向开发者, 讲解目录结构与实现。
> 配套文档: **MengPaw_Browser_skills.md**(Agent 完整操作手册: 命令面全表/
> 半自动循环/表单/抓取/排障/Playwright 对照) — 下载链接: 见本文件发布说明附件。

---

## 前文引导

### MengPaw.Browser 是什么

MengPaw.Browser(包名 `com.mengpaw.browser`)是一款**可被 AI Agent 编程控制的
Android 浏览器 APK**: 基于 WebView 的完整浏览器(人可以直接用), 同时内置
Playwright 语义命令面(`page.*` 22 条 + `browser.*` 21 条), 经 Termux 式
**am 桥**(`RunCommandService`, signature 权限)对外暴露给同签名 Shell。

一句话概括: **给人用的浏览器, 也是给 Agent 用的浏览器**——Agent 可以像操作
Playwright 一样, 驱动它打开网页、全页截图、坐标点击、填表、提取内容、管理标签页。

### 为什么需要它

- **Agent 需要"真实浏览器"**: 纯 HTTP 抓取拿不到登录后内容 / JS 渲染页面 / 强反爬
  页面; 真实浏览器自带会话、Cookie 与 JS 执行环境。
- **Playwright 语义, 零学习成本**: 命令名与参数对齐 LLM 训练语料中最熟悉的
  Playwright API, Agent 无需额外学习自定义命令。
- **独立 APK, 与 Shell 松耦合**: 浏览器独立版本节奏、独立仓库; Shell 经 am 桥
  控制, 无需为浏览器写专门插件。
- **半自动协作**: `page.load` 一次完成"导航 + 全页分段截图 + 坐标系统", Agent
  看图即可坐标点击, 往返少、上下文省。
- **安全可控**: am 桥 signature 权限(仅同签名 Shell 可调)+ 命令面白名单 +
  输出路径限制; 9880 桥与开放模式已退役(决策 #7), 第三方接入不再支持。

### 怎么读本文档

- **目标: 让 Shell Agent 经 am 桥控制浏览器并开始操作** → 读**第一部分 快速开始**
  (§1-4); 连接成功后的完整操作能力由配套文档 `MengPaw_Browser_skills.md` 传达。
- **目标: 从源码理解/修改浏览器、做二次开发、接入自己的框架** → 直接跳转
  **第二部分 从源码开始**(§5-12), 目录结构与实现细节都在那里。

---

## 第一部分 快速开始 — Shell Agent 经 am 桥控制浏览器

### 1. 控制前提

- 设备已安装 MengPaw.Browser APK(包名 `com.mengpaw.browser`)与 **MengPaw Shell(同签名)**
- 控制通道为 **Termux 式 am 桥**(`RunCommandService`), 受 signature 权限
  `com.mengpaw.permission.RUN_BROWSER_COMMAND` 保护——**仅同签名 Shell 可调**,
  第三方 App 调用会被系统拒绝(9880 HTTP 桥与开放模式已退役, 第三方接入不再支持)
- Agent 具备两项能力: `am` 命令执行(唤起浏览器/调命令) / 读取公共目录文件
  (查看截图与输出)

### 2. 两步调用

```bash
# ① 唤起浏览器(未运行或被杀时 am 桥不可达, 必须先唤起)
am start -a com.mengpaw.action.OPEN_URL --es url "https://example.com"

# ② 执行浏览器命令: 输出落盘公共目录, Shell 经 agent.read / grep / head / tail 读回
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.content --head 20" \
  --es com.mengpaw.browser.RUN_COMMAND_OUTPUT /storage/emulated/0/MengPaw/out.txt
```

浏览器进程被杀后 am 桥不可达, 重新唤起即恢复; 未运行时报
「浏览器未就绪(请先打开 MP 浏览器再调用)」。

### 3. 安全模型

| 层 | 措施 |
|----|------|
| 调用方认证 | am 桥 signature 权限 `RUN_BROWSER_COMMAND`, 仅同签名 Shell 可调 |
| 命令面 | am 桥 payload 白名单 `page.*`/`browser.*`, 拒绝任意 shell 命令 |
| 输出路径 | 输出文件限制在公共目录 `MengPaw/` 下, 禁止系统路径 |
| 存储 | `MANAGE_EXTERNAL_STORAGE`, 首启弹窗; 拒绝后每次 `page.load` 提示重授 |

### 4. 连接成功后的操作

连接建立后, 浏览器的全部操作能力(43 条命令全表 / 半自动循环 / 表单自动化 /
网页抓取 / 排障速查 / Playwright 对照)由配套文档
**MengPaw_Browser_skills.md** 传达, 本开发文档不再重复。

> 下载链接: 见本文件发布说明附件(用户上传 skills 文档后补充实际链接)。

---

## 第二部分 从源码开始 — 目录结构与实现

### 5. 仓库与构建

本仓库是 **MengPaw 按 APK 产物拆分后的浏览器独立仓库**(rootProject `MengPawBrowser`,
仅 `:mengpaw-browser` 一个模块)。共享地基(微内核/Android 适配/设计系统)不在本仓库,
经 **JitPack** 依赖主仓库发布构件 `com.github.WowBlueStudio.MengPaw:<module>:<tag>`。

- 模块: `mengpaw-browser`(Gradle Android application, 独立 APK `com.mengpaw.browser`)
- 版本: 独立节奏 v0.9.x, **单点** `gradle.properties` 的 `mengpaw.browser.version`
  (与 `mengpaw-browser/build.gradle.kts` 同步, versionCode 也在该文件)
- 共享地基版本: `gradle.properties` 的 `mengpaw.foundation.version`(指向主仓库 kernel tag;
  本地验证可用 `mengpaw.useLocal=true` 走 mavenLocal, 生产走 JitPack)
- 依赖: `mengpaw-kernel`(CLI 执行/端口表/错误码)、`mengpaw-core`(DataPaths/Logger)、
  `mengpaw-design-system`(ArcoTheme)
- 构建: `.\gradlew.bat :mengpaw-browser:assembleDebug` /
  `.\gradlew.bat :mengpaw-browser:assembleRelease`(release 关闭混淆与资源收缩, 见 build.gradle.kts)
- 测试: `.\gradlew.bat :mengpaw-browser:testDebugUnitTest`(纯 JVM 单测)
- 产物: `mengpaw-browser/build/outputs/apk/{debug,release}/mengpaw-browser-v<ver>-{debug,release}.apk`
  (版本发布需在 GitHub/Gitee Release 附带 release APK, 由 Shell 主应用 `update` 插件捎带更新)

### 6. 目录结构(文件地图)

```
com.mengpaw.browser
├── BrowserActivity.kt         # APK 入口: 状态桥/权限引导/am 桥共享实例
├── BrowserApp.kt              # Compose 主 UI (标签页/地址栏/对话框状态)
├── BrowserAppDialogs.kt       # 对话框渲染层 (状态提升, 参数显式传入)
├── BrowserContentArea.kt      # 内容区 (NewTabPage / 多标签 WebView)
├── BrowserDarkMode.kt         # 暗色模式注入
├── bridge/                    # BrowserBridge / BrowserScripts / FullPageScreenshotter
├── data/                      # BrowserPrefs / BrowserTypes / HistoryStore
├── plugin/                    # BuiltinBrowserPlugin + 4 命令组 + BrowserCommandContext
├── service/                   # RunCommandService (am 桥) / GoogleTranslate
├── ui/                        # TopBar / 设置 / 书签 / 历史 / 标签页等 Compose UI
├── util/                      # AdBlocker / BrowserStorage / DownloadUtil / SmartNavigate
└── web/                       # WebViewFactory / MdViewer*
```

关键生命周期:

- `BrowserActivity.onCreate`: 初始化 DataPaths/Logger → 绑定 Quick Click/截图设置到
  `BuiltinBrowserPlugin` → `BuiltinBrowserPlugin.shared` 供 am 桥复用 →
  首启申请「所有文件访问」。
- `BrowserActivity.onDestroy`: 销毁全部 WebView、清空状态桥。
- 9880 HTTP 桥(`McpHttpServer`/`McpAuthPolicy`/`BrowserMcpTools`)已随决策 #7 退役删除。

### 7. 命令面实现

注册源头: `BuiltinBrowserPlugin.commands` =
`BrowserTabCommands` + `BrowserPageCommands` + `BrowserQueryCommands` +
`BrowserPlaywrightCommands`。共 43 条(page.* 22 + browser.* 21)。

- `BrowserPlaywrightCommands`: `page.*` 22 条 — 参数解析支持位置参数 +
  `--flag` 值(`wait/max-height/grep/head/tail/timeout`)+ 布尔 flag(`-i` 等)
- `BrowserTabCommands`: `tabs` / `tab` / `tab.open` / `tab.close` / `tab.all`
- `BrowserPageCommands`: `inject` / `diff` / `preload` / `wait` / `wait.nav` /
  `cookies` / `cookies.set` / `cookies.clear` / `dialog.accept` / `dialog.dismiss`
- `BrowserQueryCommands`: `visible` / `enabled` / `storage` / `viewport` /
  `userAgent` / `version`

命令实现统一接收 `(List<String>, ExecutionContext) -> ExecutionResult`, 经
`BrowserCommandContext` 访问 WebView 桥(`BrowserBridge`)/标签页状态/截图器。

### 8. 调用通道实现 — am 桥(`RunCommandService`)

```text
action:  com.mengpaw.browser.RUN_COMMAND
extra:   com.mengpaw.browser.RUN_COMMAND_ARGUMENTS = "-c,<命令串>"
extra:   com.mengpaw.browser.RUN_COMMAND_OUTPUT    = <输出文件路径, 公共目录>
extra:   com.mengpaw.browser.RUN_COMMAND_BACKGROUND = true
权限:    com.mengpaw.permission.RUN_BROWSER_COMMAND (signature)
白名单:  仅 page.* / browser.* 前缀
```

`RunCommandService` 引号感知分词, 经 `BuiltinBrowserPlugin.shared` 执行命令,
输出落盘后由调用方读取。浏览器未运行时报「浏览器未就绪(请先打开 MP 浏览器再调用)」。

> 9880 HTTP 桥(`McpHttpServer`)与 MCP 开放模式已按决策 #7 退役删除:
> 浏览器侧不再有 HTTP 桥/token/`GET /health`/`POST /mcp`;
> kernel 侧文档表/提示词节与 mengpaw-connectors 的 browser-mcp-plugin 清理
> 留待主仓库执行(见 browser-autopilot-plan.md §九)。

### 9. 安全模型

| 层 | 措施 |
|---|------|
| am 桥 | signature 权限 `RUN_BROWSER_COMMAND`, 仅同签名 Shell 可调 |
| 命令面 | am 桥 payload 白名单 `page.*`/`browser.*`, 拒绝任意 shell |
| 输出路径 | am 桥输出限制在公共目录 `MengPaw/` 下 |
| 存储 | `MANAGE_EXTERNAL_STORAGE`, 首启弹窗; 拒绝后每次 `page.load` 提示重授 |

9880 桥与开放模式已退役(决策 #7)——不再有 token/免认证通道, 攻击面收敛为
单一 am 桥 + signature 权限。

### 10. 存储与截图

- 截图/输出落盘: `/storage/emulated/0/MengPaw/截图存档`(公共目录)
- 超长页分段: 每段 ≈ 视口高, 上限 30 段, 超出标注 `partial:true`
- 坐标系统: 段图坐标 → 页面坐标由浏览器自动还原 (缩放比/段偏移)
- 设置持久化: `BrowserPrefs`(SharedPreferences `mp_browser`)

### 11. 测试

`src/test/kotlin/com/mengpaw/browser/`:

- `util/SmartNavigateTest.kt` / `util/AdBlockerTest.kt`

改命令面/桥/安全逻辑必须补对应单测; 核心链路改动跑
`:mengpaw-browser:testDebugUnitTest` 全绿。

### 12. 开发规范

- 命令核对以注册处为准(`BuiltinBrowserPlugin.commands` 聚合, 不凭 grep 印象)
- 新增命令四源同步: 命令注册 / `MengPaw_Browser_skills.md` / 开发指南 / 提示词
- 端口单一事实源: `Ports.kt`(9876 内核保留 / 9881 MCP 网关; 9880 浏览器 MCP 已退役,
  常量随主仓库 kernel 清理)
- 新 `.kt` 必须带 SPDX 双许可头; 单文件 ≤400 行; 禁 `!!`
- 双许可: `AGPL-3.0-or-later OR LicenseRef-Commercial`

# CHANGELOG — MengPaw Browser

> 浏览器独立仓库版本记录。版本单点: `gradle.properties` 的 `mengpaw.browser.version`。
> 许可: AGPL-3.0-or-later OR LicenseRef-Commercial。

## 未发布 — PDF 预览 (pdf.js)

### 新增

- **PDF 预览** (对齐 Markdown 预览形态的 WebView 对话框):
  - 支持**放大/缩小**(工具栏 −/＋/适应宽度按钮)与**文字选取**(pdf.js 文本层, 按住拖动选中/复制)
  - 支持**网络文件**(`.pdf` URL 导航/链接点击被拦截并预览)与**本地文件**
    (file/content `.pdf` VIEW intent — 文件管理器/SAF/FileProvider 打开)
  - 引擎 `assets/pdf_viewer`: pdf.js 2.16.105 + 中文字形 CMap(packed) + viewer 模板/css/js
  - 数据链路: 检测(`isPdfUrl`/`PdfUtil`)→ 取数(HTTP/content/file, ≤30MB)→ 写缓存 →
    base64 内联渲染; 分页懒加载窗口渲染 + 页面回收
- 主要文件: `util/PdfUtil.kt` / `web/PdfViewerHtml.kt` / `web/PdfViewerWebView.kt` /
  `ui/BrowserPdfViewerDialog.kt` + `assets/pdf_viewer/*`

### 注意

- 受密码保护的 PDF 暂不支持输入密码 (提示后关闭)
- 真机自测待做 (pdf.js 在 WebView 的渲染/文字层/中文 CMap 需人工验证)

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

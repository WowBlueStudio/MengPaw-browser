# MengPaw_Browser_skills.md — 浏览器命令手册 (Shell Agent 专用)

> 适用对象: 经 am 桥控制 MengPaw.Browser APK 的 MengPaw Shell Agent(同签名)。
> 版本: v0.9.0 · 2026-08-29 · 本文件覆盖浏览器全部命令面与操作技巧。
> 9880 HTTP 桥与开放模式已退役(决策 #7), 第三方接入不再支持, 仅同签名 Shell 可调。
> 开发者视角的架构细节见同目录 `MengPaw.Browser 开发文档.md`。

## 1. 快速上手(2 步)

```bash
# 1. 唤起浏览器(Android 设备上执行)
am start -a com.mengpaw.action.OPEN_URL --es url "https://example.com"

# 2. 执行命令: 输出落盘公共目录, Shell 经 agent.read / grep / head / tail 读回
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.content --head 20" \
  --es com.mengpaw.browser.RUN_COMMAND_OUTPUT /storage/emulated/0/MengPaw/out.txt
```

> 浏览器命令可融入 Shell 的 Linux 命令流: 输出落盘后直接接 `grep`/`head`/`tail` 管道处理。

## 2. 唤醒浏览器

浏览器未运行(或被杀)时, am 桥不可达, 必须先唤起:

| Intent | 用途 |
|--------|------|
| `am start -a com.mengpaw.action.OPEN_URL --es url <url>` | 唤醒 + 打开 URL (推荐) |
| `am start -a android.intent.action.VIEW -d <http(s)://...>` | 系统级打开网页 |
| 桌面图标 / `MAIN + LAUNCHER` | 手动唤醒 |

唤起后 am 桥即可用 (BrowserActivity onCreate 初始化 `BuiltinBrowserPlugin.shared`),
无需手动启用。未运行时报「浏览器未就绪(请先打开 MP 浏览器再调用)」。

## 3. 命令面总览

命令分两组: `page.*`(22 条, Playwright 语义, 主用)、`browser.*`(21 条, 保留能力)。

### 3.1 page.* — 主命令面

| 命令 | 说明 |
|------|------|
| `page.load <url> [--max-height N]` | **半自动合体**: 导航 + 全页分段截图 + 坐标系统 (推荐起手) |
| `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航 + 精确等待 |
| `page.screenshot [--full] [--view]` | 截图; 只回路径 + 尺寸/坐标; 超长按段 |
| `page.screenshot.element <css>` | 元素截图 |
| `page.click <seg> <x> <y>` / `page.click <css>` | 段图坐标点击 / 选择器点击 |
| `page.fill <css> <text>` | 输入 |
| `page.select <css> <value>` / `page.submit <css>` | 下拉选值 / 提交表单 |
| `page.check` / `page.uncheck` | 勾选/取消 |
| `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 提取正文 + 过滤 |
| `page.text <css>` / `page.attr <css> <name>` | 元素文本 / 属性 |
| `page.wait_selector <css> [--timeout N]` | 等待元素出现 |
| `page.scroll <x> <y>` / `page.scroll_by <dy>` | 绝对/相对滚动 |
| `page.eval <js>` | 执行 JS |
| `page.url` / `page.title` | 当前页信息 |
| `page.back` / `page.forward` | 历史导航 |
| `page.key <key>` | 按键 (Enter/Tab/ArrowDown/单字符) |

### 3.2 browser.* — 保留命令

- 标签页: `tabs` / `tab` / `tab.open` / `tab.close` / `tab.all`(最多 4 标签)
- 效率: `inject` / `diff` / `preload`
- 等待: `wait` / `wait.nav`
- 存储/Cookie: `storage` / `cookies` / `cookies.set` / `cookies.clear`
- 对话框: `dialog.accept` / `dialog.dismiss`
- 查询/设置: `visible` / `enabled` / `viewport` / `userAgent` / `version`

## 4. 半自动循环(推荐工作流)

```text
page.load https://example.com        # 一次完成: 导航 + 分段截图 + 坐标系统
page.click 1 320 480                  # 看图 → 按段图坐标点击 (段 1)
page.scroll_by 800                    # 滚动后 page.screenshot --full 核对
page.content --grep "价格" --head 20  # 过滤提取, 不进上下文
```

返回格式示例:

```text
## page.load 完成
URL: https://example.com
段数: 3 (partial: false)
段 1: /storage/emulated/0/MengPaw/截图存档/page_..._seg1.png (1080 × 2400, 缩放 0.44)
坐标系统: page.click <seg> <x> <y> — 框架自动还原页面坐标
```

## 5. 调用通道 — am 桥(唯一通道)

```bash
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.goto https://example.com" \
  [--es com.mengpaw.browser.RUN_COMMAND_OUTPUT /storage/emulated/0/MengPaw/out.txt]
```

signature 权限 `RUN_BROWSER_COMMAND` 仅同签名应用可调; 输出落盘后自行读取。
payload 白名单仅 `page.*` / `browser.*`, 输出路径限制在公共目录 `MengPaw/` 下。

> 9880 HTTP 桥与「开放 MCP 控制」已退役(决策 #7): 无 token、无免认证通道,
> 浏览器仅接受同签名 Shell 经 am 桥控制, 第三方进程无接入方式。

## 6. 表单自动化

```text
page.goto https://example.com/login
page.fill #username <用户>
page.fill #password <密码>
page.click button[type=submit]
page.title                                  # 验证跳转, 不要假定成功
```

注意: CAPTCHA 无法自动过 — 引导用户手动完成; 文件上传无 API — 引导用户操作;
输入疑似被清空 → type 后 `page.eval` 验证 value; 敏感凭据不要写进命令历史。

## 7. 网页抓取

```text
page.load https://example.com/list          # 唤醒 + 截图
page.content --head 50                      # 提取结构
page.eval JSON.stringify(Array.from(document.querySelectorAll('a')).map(a=>a.href).filter(h=>h.includes('/article/')))
page.eval var n=document.querySelector('.next');if(n){n.click();'next'}   # 分页
```

抓取策略: 需登录/JS 渲染/反爬强的页面走浏览器(带 cookie/JS 会话); 静态批量抓取可
自行用 HTTP 客户端并发; 403/验证码 → 换浏览器通道重试; 限流 → 逐条间隔。

## 8. 排障速查

| 现象 | 处理 |
|------|------|
| 「浏览器未就绪」/ am 桥无输出 | 浏览器未运行 → 先唤起 (§2) |
| `page.load` 提示存储权限 | 未授予「所有文件访问」→ 浏览器首启弹窗或系统设置授权 |
| `page.click` 错位/超界 | 先 `page.screenshot --full` 刷新段图, 用返回的段号 + 坐标 |
| `WebView not available` | 无打开标签页 → 先 `OPEN_URL` 开页 |
| `Selector not found` | 元素未加载/在 iframe → `page.eval` 探测 DOM, 先等加载 |
| 页面提取为空 | JS 渲染未完成 → `page.goto` 等加载后再 `page.content` |
| 分段截图 `partial:true` | 超长截断 (30 段上限) 属正常 → 按已返回段操作或滚动重截 |

## 9. Playwright 对照(速查)

| Playwright | MengPaw |
|------------|---------|
| `page.goto(url)` | `page.goto <url>` |
| `page.goto + screenshot(fullPage)` | `page.load <url>` |
| `mouse.click(x, y)` | `page.click <seg> <x> <y>` |
| `page.fill(sel, text)` | `page.fill <css> <text>` |
| `page.selectOption()` | `page.select <css> <value>` |
| `page.evaluate(js)` | `page.eval <js>` |
| `page.waitForSelector()` | `page.wait_selector <css>` |
| `page.content()` | `page.content [--grep] [--head]` |
| `page.keyboard.press()` | `page.key <key>` |
| `page.mouse.wheel()` | `page.scroll_by <dy>` |

差异: 选择器仅 CSS(无 XPath); `--wait networkidle` 为近似实现; 截图只回路径;
超长页按段返回, 点击用段号 + 段内坐标。

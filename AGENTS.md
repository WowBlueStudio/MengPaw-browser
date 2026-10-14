# AGENTS.md — MengPaw Browser 会话工作规则

> 面向在本仓库(`D:\MengPaw-browser`)工作的 Agent。**开工先读本文件。**
> 仓库定位与耦合边界见 `README.md`; 浏览器架构与实现细节见 `mengpaw-browser/docs/`。

## 1. 会话边界 (用户定案 2026-09-10)

**Browser 会话只做 Browser 仓库的事, 不管 Shell 的事。**

- ✅ 可做: 本仓库的源码 / 文档 / 测试 / 构建 / 发版 / 双远端推送
- ❌ 不做 (属 Shell / 主仓库事务):
  - 改动或"顺手对齐"主仓库 `D:\MengPaw` 的源码、文档、索引(如 `docs/INDEX.md`)
  - 推送(`git push`)主仓库、打主仓库 tag、创建主仓库 Release
  - Shell 侧的版本迭代、APK 构建与上传
- Shell 侧的迭代与上传由用户在 **Shell 会话**中提出; 本会话不主动代劳, 也不主动询问"要不要顺手改主仓库"。

## 2. 仅存的两项跨仓耦合

本项目已与 MengPaw Shell 脱钩(见 `README.md` 的「项目定位与耦合边界」), 跨仓只保留两项:

| 耦合 | 方向 | 本会话怎么做 |
|---|---|---|
| 命令手册同步 | → Shell 侧 | `mengpaw-browser/docs/MengPaw_Browser_skills.md` 随版本对齐; 它是**唯一**需要同步给 Shell 的资产 |
| Android 开发经验共维护 | ↔ 主仓库 | 可写入主仓库 `docs/lessons.md`, 但**只 commit、不 push、不发布** —— 主仓库的推送/Release 留给 Shell 会话 |

## 3. 发布要点 (Browser 独立发版)

- **四个版本点必须同步**: `gradle.properties` 的 `mengpaw.browser.version` +
  `mengpaw-browser/build.gradle.kts` 的 `.orElse("...")` 兜底值 + **`versionCode`(字面量, 必须手改,
  漏改则客户端识别不出更新)** + 产物复核(`aapt2 dump badging`)
- 构建用固定参数 `--build-cache --console=plain`, **不要 clean**(会丢构建缓存导致全量重编)
- 双远端推送: Gitee 先行; GitHub 若报 `Connection was reset` / 连不上, 按 45s 间隔重试 5~8 次,
  仍失败再走 SSH-over-443 兜底(临时密钥登记后**用完即删**)
- 双平台 Release 必须**同批创建**并附 `mengpaw-browser-v<ver>-release.apk`(缺一不可)
- **发 Release 的凭据（本机）**:
  - GitHub: 已登录的 `gh` CLI（账号 `XMeng5257`），`gh release create <tag> <apk> -F <notes>`
  - Gitee: 令牌在**用户级环境变量 `GITEE_TOKEN`**（32 位）——当前会话进程**通常不继承**它，
    必须显式读取 `[Environment]::GetEnvironmentVariable("GITEE_TOKEN","User")`（命令里不要回显令牌）
  - Gitee 建 Release: `POST https://gitee.com/api/v5/repos/{owner}/{repo}/releases`（`access_token` 放 form 可用）
  - Gitee 传附件: `POST .../releases/{id}/attach_files` —— **`access_token` 必须放 query**，
    放 form 会 `401 Unauthorized`（已实测）

## 4. 红线

- 新建 `.kt` / `.kts` 必须带 SPDX 双许可头; 文件行数 ≤400
- 禁止 `!!`; 文件 IO 必须 try/catch
- 未经用户明确指令, 不发版、不打 tag、不推送远端
- 真机自测由用户完成, Agent 不遥控真机; 未验证项必须在 CHANGELOG 显式标注
- **网页测试一律在模拟器里做（用户定案 2026-10-14）**: 任何"站点在某浏览器里渲染/交互如何"的验证
  （截图、探针、点击、滚动、A/B 配置），都在本机模拟器完成，**不要去折腾用户的平板**；
  平板只用于最终交付（安装发行包）。
  - 现成环境: AVD **`MpTablet`**（2880×1840、density 306，与用户平板同规格）+
    系统镜像 `system-images;android-35;google_apis;x86_64`；`adb -s emulator-5554`；
    headless 启动 `emulator -avd MpTablet -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5554`
  - 打开网址用 intent（App 注册了 VIEW http/https）:
    `adb -s emulator-5554 shell am start -n com.mengpaw.browser/.BrowserActivity -a android.intent.action.VIEW -d "<url>"`
  - 截图 `adb -s emulator-5554 exec-out screencap -p > x.png`；点击/滑动用 `input tap|swipe`
  - 对照浏览器（Chrome）注意它会卡在 `TabbedModeFirstRunActivity` 首次引导，需要先跳过引导或用
    `adb root` + Chromium command-line 关掉 FRE，不能直接拿首启页当对照
  - 站点产物抓取放 `_trek/`，模拟器产物放 `_emu/`（均已在 `.gitignore`，**不要入库**）

## 5. 工作方式 (**用户定案 2026-10-14 — 开工即按此执行**)

### 5.1 并行子代理优先

- **能并行的就不串行**: 独立工作流(调研 / 只读审查 / 站点与依赖分析 / 机械拆分 / 模块改造)
  一律用 `subagent` 并行分发, **主会话只做关键路径 + 整合 + 门禁 + 交付**。
- 派活要**自包含**: 给足背景、已知硬事实(设备参数、实测数值、已确认的 file:line)、
  交付物格式与**禁止项**; 长任务卡先写到临时目录再让子代理 read, 不要把长文塞进 prompt。
- **工作树冲突纪律**(多子代理同写一个仓库时):
  - 每个子代理**只允许改明确列出的路径**; 主会话与子代理**不碰同一文件**;
  - 谁的文件谁提交; 确需连带提交时在 commit message 写明依赖关系;
  - 并发行改动导致本地门禁红**不代表自己改错**: 先看 `git status` 归因, 别急着改别人的文件。
- 子代理产出**必须复核**: 主会话独立跑门禁 + 抽查关键实现; 子代理自报"已完成"不算证据。
- 子代理易漏的兜底(超时 / 尺寸断言 / 回调解绑 / 协议分流)要**显式写进任务卡**。

### 5.2 公域知识优先

- 平台级疑难(WebView/Chromium 行为、Android 兼容、协议语义)**先取证再动手**: 官方文档与 CL、
  Chromium/AOSP 文档、StackOverflow 高票已采纳答案、caniuse/MDN 等。
- 结论**必须区分**「有来源支撑」与「我的推断」; 有来源的给 markdown 链接, 推断的标出来并设计验证方式。
- 站点类问题**先抓真实产物再分析**: 抓 HTML/CSS/JS 到本地临时目录(**不入库**, 进 `.gitignore`),
  用 grep + 上下文片段定位触发逻辑与高度规则, 不靠猜。
- 能用**一次真机采样**钉死的就不要反复猜: 要采集的数值(JS 表达式)压到最少, 经日志 tag 输出
  (`adb logcat -s MpWebConsole`), 让用户跑一次即可定论。

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

## 4. 红线

- 新建 `.kt` / `.kts` 必须带 SPDX 双许可头; 文件行数 ≤400
- 禁止 `!!`; 文件 IO 必须 try/catch
- 未经用户明确指令, 不发版、不打 tag、不推送远端
- 真机自测由用户完成, Agent 不遥控真机; 未验证项必须在 CHANGELOG 显式标注

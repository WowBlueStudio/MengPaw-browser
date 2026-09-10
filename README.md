# MengPaw Browser (独立浏览器)

MengPaw Browser 是一个**独立的 Android 浏览器项目**——「半自动武器」形态: 人可直接使用的完整浏览器,
同时经 am 桥(signature 权限 `RUN_BROWSER_COMMAND`)对外暴露 Playwright 语义命令面, 供外部程序半自动控制。

> **项目定位: 中性的独立工具, 不隶属于 MengPaw Shell。**
> Shell 把 Browser 当作重要工具使用(乃至主动检查/安装它的更新), 那是 **Shell 自身的集成行为**,
> 与本项目的独立演进并不相悖——任何第三方 Shell 类软件只要愿意, 都可以按同一份命令手册适配
> MengPaw.Browser。本项目**正在逐步脱离 MengPaw Shell**, 目标是成为一个完全独立的项目。

本仓库由 MengPaw 主仓库按 APK 产物拆分而来, 现已独立演进, 仅包含浏览器模块源码。
共享地基(微内核 / Android 适配 / 设计系统)经 **JitPack 依赖** 主仓库发布构件:
`com.github.WowBlueStudio.MengPaw:<module>:<tag>`。

## 项目定位与耦合边界

与 Shell / 主仓库**仅保留两项耦合**, 其余**全部脱钩**:

| 保留的耦合 | 说明 |
|---|---|
| **Browser Skill 同步** | 命令手册 `mengpaw-browser/docs/MengPaw_Browser_skills.md` 是本项目唯一需要同步给 Shell 侧的资产 (Shell 据此调用浏览器) |
| **Android 开发经验共同维护** | 踩坑与发布经验共同沉淀于主仓库经验库 `docs/lessons.md` |

已脱钩项(不再要求与 Shell 同步):

- **版本**: 独立节奏 (当前 v0.10.x), 不跟随主项目 `mengpaw.version`
- **发版**: 本仓库自行发布 Release 并附带 APK 资产, 与 Shell 版本无绑定关系
- **更新链路**: 本项目不依赖任何 Shell 组件; Shell 侧主动更新 Browser 属 Shell 自己的行为
- **插件市场 / 文档归属**: 本仓库不含 `plugins.json`, 文档以本仓库为准

## 仓库关系

| 仓库 | 内容 | 与本项目的关系 |
|------|------|------|
| **`WowBlueStudio/MengPaw-Browser`** (本仓库) | Browser APK 独立源码, 独立版本与发版 | — |
| `WowBlueStudio/MengPaw` | 主仓库: 微内核 kernel + core + design-system + Shell APK + 内置插件 | 仅提供**共享地基** JitPack 构件与经验库; **不是本项目的上级** |

## 独立版本线

- Browser 走**独立版本节奏** (当前 v0.10.x), 不跟随主项目 `mengpaw.version`。
- 版本单点: `mengpaw-browser/build.gradle.kts` 的 `browserVersion` + `gradle.properties` 的 `mengpaw.browser.version` (需同步)。
- 共享地基版本: `gradle.properties` 的 `mengpaw.foundation.version` (指向主仓库 kernel tag)。

## 构建

```bash
# 共享地基经 mavenLocal (本地验证) — 先在主仓库 publishToMavenLocal
# 或经 JitPack (生产): 设置 gradle.properties mengpaw.foundation.version 为主仓库 tag

./gradlew :mengpaw-browser:assembleDebug
./gradlew :mengpaw-browser:assembleRelease
./gradlew :mengpaw-browser:testDebugUnitTest
```

APK 产物: `mengpaw-browser/build/outputs/apk/{debug,release}/mengpaw-browser-v<ver>-{debug,release}.apk`

> 本地验证提示: `settings.gradle.kts` 的 `mavenLocal()` 用于本地开发, 远程 CI 应移除, 仅保留 `jitpack.io`。

## 发布与更新

本仓库**独立发布**: 每次 Release 在 GitHub/Gitee 附带 `mengpaw-browser-v<ver>-release.apk` 资产。

Shell 侧若主动检查并安装该资产, 属 **Shell 自身的集成行为**——本项目不感知、不依赖, 也不需要与
Shell 版本对齐; 任何第三方 Shell 类软件同样可按命令手册接入 MengPaw.Browser。

## 双许可

- 社区版: AGPL-3.0-or-later (见 LICENSE)
- 商业授权: LicenseRef-Commercial (见 COMMERCIAL-LICENSE.md)

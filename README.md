# MengPaw Browser (独立浏览器)

MengPaw Agent 的独立浏览器应用——「半自动武器」+ am 桥控制(仅同签名 Shell)。

本仓库是 **MengPaw 按 APK 产物拆分后的浏览器独立仓库**,仅包含浏览器模块源码。
共享地基(微内核 / Android 适配 / 设计系统)经 **JitPack 依赖** 主仓库发布构件:
`com.github.WowBlueStudio.MengPaw:<module>:<tag>`。

## 仓库关系

| 仓库 | 内容 | 依赖 |
|------|------|------|
| `WowBlueStudio/MengPaw` | 主仓库: 微内核 kernel + core + design-system + Shell APK + 内置插件 | — |
| **`WowBlueStudio/MengPaw-Browser`** (本仓库) | Browser APK 独立源码 | JitPack 依赖主仓库共享地基 |

## 独立版本线

- Browser 走**独立版本节奏** (v0.8.x), 不跟随主项目 `mengpaw.version`。
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

## 自动更新

Browser 的更新由 Shell 主应用的 `update` 插件「捎带」管理 (Shell 侧解析本仓库的 release 获取 Browser APK)。
本仓库每次发布需在 GitHub/Gitee release 附带 `mengpaw-browser-v<ver>-release.apk` 资产。

## 双许可

- 社区版: AGPL-3.0-or-later (见 LICENSE)
- 商业授权: LicenseRef-Commercial (见 COMMERCIAL-LICENSE.md)

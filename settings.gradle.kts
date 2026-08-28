// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本地开发: 优先用 publishToMavenLocal 发布的共享地基构件 (快速验证)。
        // 通过 gradle 属性 mengpaw.useLocal 控制 (true=本地验证, false/缺省=走 JitPack)。
        // 远程 CI 应设为 false (缺省), 仅保留 jitpack.io。
        val useLocal = providers.gradleProperty("mengpaw.useLocal").orElse("false").get() == "true"
        if (useLocal) {
            mavenLocal()
        }
        google()
        mavenCentral()
        // 生产: 共享地基从 JitPack 拉取 (com.github.WowBlueStudio.MengPaw:<module>:<tag>)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MengPawBrowser"
include(":mengpaw-browser")

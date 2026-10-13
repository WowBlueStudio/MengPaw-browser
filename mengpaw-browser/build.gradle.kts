// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 独立版本节奏 (不跟随主项目 mengpaw.version) — 单点数据源, 版本迭代只改这里
// v0.8.0: 半自动武器 (page.* 命令面 + am 桥 + 超长页分段坐标 + 公共目录截图)
// v0.8.1: MCP 开放模式 (第三方 Agent 经 9880 免认证控制, Playwright 式)
// v0.9.0: 逻辑重构 — 退役 9880 桥与开放模式, am 桥单通道 (决策 #7); batch/q 去重 (决策 #4)
// v0.10.0: PDF 预览 — pdf.js 渲染 (放大/缩小/文字选取), 网络 .pdf URL + 本地 file/content
// v0.10.1: 项目定位纠正 — 与 MengPaw Shell 脱钩 (中性独立项目, 仅保留手册同步+经验共维护)
// v0.10.2: 地址栏/导航修复 (获焦全选+待办导航+编辑中不回写) + 明文 http 放行 + 控件/按键普查
// v0.10.3: 三层闭环+九维审计全量修复 (21 条 browser.* 命令可达/截图落盘校验/书签与会话 JSON/
//          路径穿越/输出校验/暗色关闭/标签挂起/版本号派生)
val browserVersion: String = providers.gradleProperty("mengpaw.browser.version").orElse("0.10.3").get()

/**
 * versionCode 由 versionName 派生（v0.10.3 起）— 消灭「漏改字面量」这一类事故。
 *
 * 旧实现是手写字面量（AGENTS 亦自述"必须手改, 漏改则客户端识别不出更新"），
 * 漏改时 versionName 照常自增、APK 能装但更新链路判不出新旧（静默不触发更新）。
 * 派生规则 `major*10000 + minor*100 + patch`：单调递增（minor 每 +1 覆盖 100 个 patch），
 * 且与历史版本兼容（0.10.2 → 1002 > 旧字面量 18）。
 */
fun derivedVersionCode(version: String): Int {
    val parts = version.trim().split(".", "-").mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor in 0..99 && patch in 0..99) { "版本号格式须为 X.Y.Z 且 Y/Z ≤ 99: $version" }
    return major * 10000 + minor * 100 + patch
}

// 共享地基版本 — 主仓库 kernel tag, JitPack 构件 (com.github.WowBlueStudio.MengPaw:<module>:<tag>)
val foundationGroup: String = "com.github.WowBlueStudio.MengPaw"
val foundationVersion: String = providers.gradleProperty("mengpaw.foundation.version").orElse("0.44.0").get()

android {
    namespace = "com.mengpaw.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpaw.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = derivedVersionCode(browserVersion)
        versionName = browserVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Debug: no minification, fast builds
        }
        release {
            // 混淆关闭: 浏览器源码公开 (开源仓库), R8 不提供实质保护, 且本机 R8 内存不足易卡死。
            // 如需混淆, 打开 isMinifyEnabled 并确保构建机有足够内存。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "../proguard-common.pro"
            )
            isShrinkResources = false
        }
    }

    // Per-variant output naming. Must be inside buildTypes to avoid Kotlin DSL
    // type-inference issues with Boolean-returning lambdas in AGP 8.x.
    buildTypes {
        debug {
            applicationVariants.all {
                if (buildType.name == "debug") {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-browser-v${browserVersion}-debug.apk"
                        }
                    }
                } else {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-browser-v${browserVersion}-release.apk"
                        }
                    }
                }
            }
        }
    }

    val keystoreFile = project.findProperty("keystore.file") as? String ?: "mengpaw-release.jks"
    val keystoreStorePass = project.findProperty("keystore.storepass") as? String ?: ""
    val keystoreKeyPass = project.findProperty("keystore.keypass") as? String ?: ""
    val releaseKeystoreFile = rootProject.file(keystoreFile)
    if (releaseKeystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreStorePass
                keyAlias = "mengpaw"
                keyPassword = keystoreKeyPass
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    // 共享地基 — JitPack 构件 (主仓库发布), 替代原 project(":mengpaw-kernel") 等
    implementation("$foundationGroup:mengpaw-kernel:$foundationVersion")
    implementation("$foundationGroup:mengpaw-core:$foundationVersion")
    implementation("$foundationGroup:mengpaw-design-system:$foundationVersion")

    // Kotlin
    // CommonMark md→HTML 渲染 (与 design-system 同版本; design-system 以 implementation 声明不传递)
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
}

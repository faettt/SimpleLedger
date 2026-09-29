import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    // Room Gradle 插件：为每个 variant 正确接线 schema 目录并作为任务输入/输出登记，
    // 消除 debug/release 两个 KSP 任务并行导写同一 schema 文件的竞态（详见文件末尾 room{} 块）
    alias(libs.plugins.androidx.room)
}

/*
 * 发布签名读取 keystore.properties（该文件与 .jks 均被 .gitignore 忽略，绝不入库）。
 *
 * 硬性要求：文件缺失时**自动退回 debug 签名**，而不是让构建失败——
 * 新克隆仓库的人、CI、以及没有私钥的协作者都应能正常 assembleRelease。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.simpleledger.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simpleledger.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "1.5.1"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                // storeFile 相对项目根目录解析，允许 keystore.properties 只写相对路径
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式密钥则用正式签名；缺失则退回 debug 签名（保证缺密钥也能构建）
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // LocalContextGetResourceValueCall（Compose lint）在本项目实际风险有限：
        // Manifest 已用 configChanges 覆盖 uiMode/density/screenLayout 等全部配置变更
        // （Activity 不重建），且读取的是静态 dimen/color，不随运行时配置漂移。
        // 降为警告保持记录；lintRelease 对其余检查（NewApi 等）仍是会失败构建的硬门禁
        // ——2026-09 全面审查中 4 条 NewApi（含 Android 8~13 闪退）静默累积 12 个版本，
        // 根因就是从未跑过 lint。
        warning += "LocalContextGetResourceValueCall"
    }
}

// Room：导出 schema JSON 到 app/schemas/（提交进仓库，作为未来迁移测试的基线），
// 同时消除 `AppDatabase.kt:27` 那条「Schema export directory was not provided」警告。
//
// 用 Room Gradle 插件的 `schemaDirectory(...)`，而**不是**裸写 `ksp { arg("room.schemaLocation", ...) }`：
// 后者是一个对所有 variant 生效的全局参数，`kspDebugKotlin` 与 `kspReleaseKotlin` 会并发
// 读写同一个 schemas/2.json（org.gradle.parallel=true 时），导致
// `JsonDecodingException: Expected start of the object '{', but had 'EOF'`（读到另一个任务
// 正在写/已截断的文件）。插件版把 schema 目录按 variant 正确接线并登记为任务输入/输出，
// 竞态消失。这是 Room 警告信息本身推荐的做法。
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose（BOM 统一管理版本）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // 窗口度量：真读 FoldingFeature，用于折叠屏/桌面姿态判定（见 ui/WindowMetrics.kt）
    implementation(libs.androidx.window)

    // 应用锁：ProcessLifecycleOwner 监听前台/后台切换，用于「后台超过 30 秒才验证」
    implementation(libs.androidx.lifecycle.process)

    // 应用锁：BiometricPrompt 的 Google 封装（minSdk 26 上回退到指纹，稳定版）
    implementation(libs.androidx.biometric)

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 图片加载（本地文件）
    implementation(libs.coil.compose)

    // 多端同步：Argon2id 口令派生（纯 Java 直调 Argon2BytesGenerator，不注册 Security Provider，
    // 避开 Android 内置旧版 BC Provider 的类冲突；见架构设计 V1 实测）
    implementation(libs.bcprov.jdk18on)

    // 多端同步：自研最小 WebDAV 客户端的传输底座（PROPFIND/MKCOL/条件写/Range，见 V2 实测）
    implementation(libs.okhttp)

    // 多端同步：后台 30 分钟周期兜底（CoroutineWorker，见 V3 实测）
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)

    // 多端同步 JVM 单测：org.json 真实现（Android 内置同 API，android.jar 桩不可用）。
    // OpCodec / OpApplier 的操作载荷编解码在单测里必须跑真 JSON。
    testImplementation(libs.json)

    // 多端同步 JVM 单测：kxml2 提供 XmlPullParser 实现（PROPFIND 解析；
    // 生产路径用 android.util.Xml.newPullParser()，测试经解析器工厂注入本实现）
    testImplementation(libs.kxml2)
}

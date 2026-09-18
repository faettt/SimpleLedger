// 顶层构建文件：只声明插件版本，不在这里应用
buildscript {
    dependencies {
        // AGP 9 内置 Kotlin：通过 classpath 把内置 Kotlin / Compose 编译器 / KSP 升到与依赖匹配的版本
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

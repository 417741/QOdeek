plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qosed.qodeep"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qosed.qodeep"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("qodeep-release.jks")
            storePassword = "qodeep2026"
            keyAlias = "qodeep"
            keyPassword = "qodeep2026"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 正式签名，打包出来可直接安装
            signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets {
        getByName("main") {
            // 背景资源放在项目根目录 beijing/（便于在源码目录直接查看），打包时合并进 APK assets 根
            assets.srcDir(rootProject.file("beijing"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// 一键打包：gradlew packRelease
// 构建 release APK 后，改名为应用名称 QOdeep.apk，直接放到项目根目录（不在子文件夹里）
tasks.register("packRelease") {
    group = "build"
    description = "Build signed release APK and copy it to project root as QOdeep.apk"
    dependsOn("assembleRelease")
    // 配置期先捕获路径，避免 doLast 引用 Project 对象破坏 configuration cache
    val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val target = rootProject.projectDir.resolve("QOdeep.apk")
    doLast {
        val apk = apkDir.listFiles { f: File -> f.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("release APK not found in $apkDir")
        apk.copyTo(target, overwrite = true)
        println(">>> 打包完成: $target")
    }
}

dependencies {
    // legacy Xposed API：编译期引用，运行时由 LSPosed 框架提供，不打进 APK
    // 与 QEdge/吧唧/FunBox 等模块一致，LSPosed 2.x 对 legacy API 完全兼容
    compileOnly("de.robv.android.xposed:api:82")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DexKit：运行时在宿主 dex 里动态查找混淆后的类，适配未知 QQ 版本
    implementation(libs.dexkit)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
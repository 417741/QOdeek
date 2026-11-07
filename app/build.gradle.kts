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
        versionCode = 48
        versionName = "0.0.48"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystore = rootProject.file("qodeep-release.jks")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = "qodeep2026"
                keyAlias = "qodeep"
                keyPassword = "qodeep2026"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 本地有 keystore 才签正式签名；没有则产出未签名 APK
            if (rootProject.file("qodeep-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    sourceSets {
        getByName("main") {
            // 背景资源放在项目根目录 beijing/，打包时合并进 APK assets 根
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

// gradlew 一键打包：构建 release 后改名 QOdeep.apk 复制到项目根目录
tasks.register("一键打包") {
    group = "打包"
    description = "构建正式签名 APK 并复制为项目根目录的 QOdeep.apk"
    dependsOn("assembleRelease")
    // 配置期先捕获路径，避免 doLast 里引用 Project 破坏 configuration cache
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
    // legacy Xposed API：编译期引用，运行时由 LSPosed 提供，不打进 APK
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

    // 传统 View 栈 UI（对齐 FunBox）：毛玻璃卡片 + 弹窗 + 图片加载 + 动画
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)
    implementation(libs.lottie)
    implementation(libs.xpopup)
    implementation(libs.blurview)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
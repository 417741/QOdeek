plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 219
        versionName = "0.0.219"

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

    // 关键：把模块资源包 ID 从默认 0x7f 强制改成 0x69，与 QQ 宿主的 0x7f 段完全隔离。
    // 否则 R.drawable.db 这类资源 ID 在 QQ 进程里会被 QQ 自己的资源表解析成错误图片，
    // 且 injectModuleResources 的「已注入」检查（res.getString(R.string.app_name)）也会
    // 因 0x7f 段 ID 命中 QQ 字符串而误判，导致入口图标永远显示成 QQ 的蓝方块。
    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x69")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DexKit：运行时在宿主 dex 里动态查找混淆后的类，适配未知 QQ 版本
    implementation(libs.dexkit)
    implementation(libs.protobuf.javalite)

    // 传统 View 栈 UI：RecyclerView 宫格选择 + Glide 图片加载
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.glide)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
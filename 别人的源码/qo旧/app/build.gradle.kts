import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 读取签名配置（keystore.properties），避免把密码硬编码进脚本
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.qo.mk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.qo.mk"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "0.3.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
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

    // QEdge 寄生在 QQ 进程时的资源隔离：把模块资源 package-id 改成 0x69，
    // 避免与宿主 QQ（0x7f）的资源 ID 冲突
    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x69")
        localeFilters += listOf("zh")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "org/mozilla/javascript/tools/**"
            excludes += "google/protobuf/**"
            excludes += "org/mozilla/javascript/resources/Messages_fr.properties"
            excludes += "META-INF/*.version"
            excludes += "META-INF/androidx/**/LICENSE.txt"
            excludes += "DebugProbesKt.bin"
        }
    }
}

dependencies {
    implementation(project(":qedge"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 应用显示名，与 app/src/main/res/values/strings.xml 里的 app_name 保持一致。
// 用于命名打包产物：文件名直接等于应用名，避免 app-release-v 这类冗长前缀。
val appDisplayName = "QO"

// 打包后把 release APK 复制到项目根目录，方便直接查找，不用钻进 build/outputs 深层子目录
// 用带显式 @Input 属性的自定义 task，兼容 Gradle 配置缓存
abstract class CopyReleaseApkTask : DefaultTask() {
    @get:Input
    abstract val appName: Property<String>

    @get:InputFile
    abstract val srcApk: RegularFileProperty

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = srcApk.get().asFile
        val dst = outputDir.get().file("${appName.get()}.apk").asFile
        src.copyTo(dst, overwrite = true)
        println("APK 已复制到项目根目录：${dst.absolutePath}")
    }
}

val copyReleaseApkToRoot by tasks.registering(CopyReleaseApkTask::class) {
    appName.set(appDisplayName)
    srcApk.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    outputDir.set(rootProject.layout.projectDirectory)
    doNotTrackState("复制到项目根目录，输出目录不可哈希")
}

// 让 assembleRelease 完成时自动执行复制（AGP 的 assemble 任务在 afterEvaluate 后才注册完成）
afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy(copyReleaseApkToRoot)
    }
}
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
        versionName = "0.0.1"

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
            signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets {
        getByName("main") {
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

// gradlew 一键打包
tasks.register("一键打包") {
    group = "build"
    description = "Build signed release APK and copy it to project root as QOdeep.apk"
    dependsOn("assembleRelease")
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
    compileOnly("de.robv.android.xposed:api:82")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

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
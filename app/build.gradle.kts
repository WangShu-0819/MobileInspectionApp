import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localSdkProperties = Properties().also { properties ->
    rootProject.file("local.properties").inputStream().use { properties.load(it) }
}
val androidSdkRoot = file(localSdkProperties.getProperty("sdk.dir"))
val ncnnSmokeNdkRoot = androidSdkRoot.resolve("android-ndk-r30")
val ncnnSmokePackageRoot = androidSdkRoot.resolve("ncnn-20260526-android-shared")
val textileRoot = rootProject.projectDir.parentFile.parentFile
val ncnnSmokeModelRoot = textileRoot.resolve(
    "nanodet-main/nanodet-main/workspace/key_nut_thread_experiments/exp01_decoupled_retry/model_best/android_export/ncnn_20260526_opt2"
)
val ncnnSmokeFrameRoot = rootProject.projectDir.parentFile.resolve("DCIM/extracted_frames")
val ncnnSmokeParityFile = textileRoot.resolve("nanodet-main/nanodet-main/reports/ncnn_android/parity_results.json")
val ncnnSmokeAssetsDir = layout.buildDirectory.dir("generated/ncnnSmoke/androidTest/assets")
val ncnnSmokeJniLibsDir = layout.buildDirectory.dir("generated/ncnnSmoke/androidTest/jniLibs")
val ncnnSmokeNativeWorkDir = File(System.getProperty("java.io.tmpdir"), "mobileinspection-ncnn-smoke")
val ncnnSmokeNativeLibsDir = File(ncnnSmokeNativeWorkDir, "libs")
val nanoDetMainAssetsDir = layout.buildDirectory.dir("generated/nanodet/main/assets")
val nanoDetMainJniLibsDir = layout.buildDirectory.dir("generated/nanodet/main/jniLibs")
val nanoDetNativeWorkDir = File(System.getProperty("java.io.tmpdir"), "mobileinspection-nanodet-main")
val nanoDetNativeLibsDir = File(nanoDetNativeWorkDir, "libs")

android {
    namespace = "com.wearable.inspection.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wearable.inspection.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
        unitTests.isIncludeAndroidResources = true
    }

    // MigrationTestHelper 从 androidTest assets 读取历史 Room schema。
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    sourceSets["androidTest"].assets.srcDir(ncnnSmokeAssetsDir)
    sourceSets["androidTest"].jniLibs.srcDir(ncnnSmokeJniLibsDir)
    sourceSets["main"].assets.srcDir(nanoDetMainAssetsDir)
    sourceSets["main"].jniLibs.srcDir(nanoDetMainJniLibsDir)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val stageNcnnSmokeAndroidTestAssets = tasks.register<Copy>("stageNcnnSmokeAndroidTestAssets") {
    from(ncnnSmokeModelRoot.resolve("nanodet.ncnn.param")) { into("ncnn_smoke/model") }
    from(ncnnSmokeModelRoot.resolve("nanodet.ncnn.bin")) { into("ncnn_smoke/model") }
    from(ncnnSmokeFrameRoot.resolve("frame_00106_f1060.jpg")) { into("ncnn_smoke/images") }
    from(ncnnSmokeFrameRoot.resolve("frame_00045_f450.jpg")) { into("ncnn_smoke/images") }
    from(ncnnSmokeParityFile) { into("ncnn_smoke") }
    into(ncnnSmokeAssetsDir)
}

val prepareNcnnSmokeAndroidTestNative = tasks.register<Sync>("prepareNcnnSmokeAndroidTestNative") {
    from("src/androidTest/cpp")
    into(ncnnSmokeNativeWorkDir)
}

val buildNcnnSmokeAndroidTestNative = tasks.register<Exec>("buildNcnnSmokeAndroidTestNative") {
    dependsOn(prepareNcnnSmokeAndroidTestNative)
    val arm64Root = ncnnSmokePackageRoot.resolve("arm64-v8a")
    inputs.files(fileTree("src/androidTest/cpp"))
    inputs.files(fileTree(arm64Root.resolve("include")))
    inputs.file(arm64Root.resolve("lib/libncnn.so"))
    outputs.dir(ncnnSmokeNativeLibsDir)
    commandLine(
        ncnnSmokeNdkRoot.resolve("ndk-build.cmd").absolutePath,
        "NDK_PROJECT_PATH=${ncnnSmokeNativeWorkDir.absolutePath}",
        "APP_BUILD_SCRIPT=${File(ncnnSmokeNativeWorkDir, "Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${File(ncnnSmokeNativeWorkDir, "Application.mk").absolutePath}",
        "NDK_OUT=${File(ncnnSmokeNativeWorkDir, "obj").absolutePath}",
        "NDK_LIBS_OUT=${ncnnSmokeNativeLibsDir.absolutePath}",
        "NCNN_ROOT=${ncnnSmokePackageRoot.absolutePath.replace('\\', '/')}",
        "HOST_OS=windows"
    )
}

val cleanupNcnnSmokeAndroidTestNativeWork = tasks.register<Delete>("cleanupNcnnSmokeAndroidTestNativeWork") {
    delete(ncnnSmokeNativeWorkDir)
}

val stageNcnnSmokeAndroidTestNative = tasks.register<Copy>("stageNcnnSmokeAndroidTestNative") {
    dependsOn(buildNcnnSmokeAndroidTestNative)
    from(File(ncnnSmokeNativeLibsDir, "arm64-v8a/libncnn_smoke.so")) { into("arm64-v8a") }
    from(ncnnSmokePackageRoot.resolve("arm64-v8a/lib/libncnn.so")) { into("arm64-v8a") }
    into(ncnnSmokeJniLibsDir)
    finalizedBy(cleanupNcnnSmokeAndroidTestNativeWork)
}

val stageNanoDetMainAssets = tasks.register<Copy>("stageNanoDetMainAssets") {
    from(ncnnSmokeModelRoot.resolve("nanodet.ncnn.param")) { into("nanodet") }
    from(ncnnSmokeModelRoot.resolve("nanodet.ncnn.bin")) { into("nanodet") }
    into(nanoDetMainAssetsDir)
}

val prepareNanoDetMainNative = tasks.register<Sync>("prepareNanoDetMainNative") {
    from("src/main/cpp")
    into(nanoDetNativeWorkDir)
}

val buildNanoDetMainNative = tasks.register<Exec>("buildNanoDetMainNative") {
    dependsOn(prepareNanoDetMainNative)
    val arm64Root = ncnnSmokePackageRoot.resolve("arm64-v8a")
    inputs.files(fileTree("src/main/cpp"))
    inputs.files(fileTree(arm64Root.resolve("include")))
    inputs.file(arm64Root.resolve("lib/libncnn.so"))
    outputs.dir(nanoDetNativeLibsDir)
    commandLine(
        ncnnSmokeNdkRoot.resolve("ndk-build.cmd").absolutePath,
        "NDK_PROJECT_PATH=${nanoDetNativeWorkDir.absolutePath}",
        "APP_BUILD_SCRIPT=${File(nanoDetNativeWorkDir, "Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${File(nanoDetNativeWorkDir, "Application.mk").absolutePath}",
        "NDK_OUT=${File(nanoDetNativeWorkDir, "obj").absolutePath}",
        "NDK_LIBS_OUT=${nanoDetNativeLibsDir.absolutePath}",
        "NCNN_ROOT=${ncnnSmokePackageRoot.absolutePath.replace('\\', '/')}",
        "HOST_OS=windows"
    )
}

val cleanupNanoDetMainNative = tasks.register<Delete>("cleanupNanoDetMainNative") {
    delete(nanoDetNativeWorkDir)
}

val stageNanoDetMainNative = tasks.register<Copy>("stageNanoDetMainNative") {
    dependsOn(buildNanoDetMainNative)
    from(File(nanoDetNativeLibsDir, "arm64-v8a/libnanodet_ncnn_runtime.so")) { into("arm64-v8a") }
    from(ncnnSmokePackageRoot.resolve("arm64-v8a/lib/libncnn.so")) { into("arm64-v8a") }
    into(nanoDetMainJniLibsDir)
    finalizedBy(cleanupNanoDetMainNative)
}

tasks.configureEach {
    when (name) {
        "mergeDebugAndroidTestAssets" -> dependsOn(stageNcnnSmokeAndroidTestAssets)
        "mergeDebugAndroidTestJniLibFolders" -> dependsOn(stageNcnnSmokeAndroidTestNative)
        "mergeDebugAssets", "mergeReleaseAssets" -> dependsOn(stageNanoDetMainAssets)
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> dependsOn(stageNanoDetMainNative)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)

    // DPM 扫码
    implementation(libs.mlkit.barcode.scanning)
    // OCR (ML Kit text recognition for steel stamp OCR)
    implementation(libs.mlkit.text.recognition)
    // ZXing Data Matrix 兜底
    implementation(libs.zxing.core)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // EXIF
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // OpenCV
    implementation(libs.opencv)

    // Apache POI (Excel 导出，阶段 D 再引入)
    // implementation(libs.apache.poi)
    // implementation(libs.apache.poi.ooxml)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20231013") // 真实 org.json，替代 AGP stub（optString 等返回 null）
    testImplementation(libs.opencv.desktop) // Desktop OpenCV for JVM tests (DpmPreprocessor etc.)
    testImplementation(libs.androidx.room.testing) // Room 测试支持
    testImplementation(libs.androidx.compose.ui.test.junit4) // Compose UI 测试
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.robolectric:robolectric:4.13")
    debugImplementation(libs.androidx.compose.ui.test.manifest) // Compose UI test manifest

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:core:1.6.1")
}

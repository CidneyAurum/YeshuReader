import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// release 签名来自仓库根目录下 gitignored 的 keystore.properties，格式（storeFile 相对仓库根目录）：
//   storeFile=yeshu-release.jks
//   storePassword=******
//   keyAlias=yeshu
//   keyPassword=******
// 四项缺任意一项（或整个文件不存在）时跳过签名配置，assembleRelease 仍能产出未签名 APK，不会直接失败。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile: String? = keystoreProperties.getProperty("storeFile")
val releaseStorePassword: String? = keystoreProperties.getProperty("storePassword")
val releaseKeyAlias: String? = keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword: String? = keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "app.yeshu.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.yeshu.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 仅在提供了完整的 keystore.properties 时才使用 release 签名
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // SetTextI18n：本项目用户可见文案全部硬编码中文（无多语言计划），
        // 逐处 getString 会引入一整套资源表而不改变任何用户可见行为，统一关闭。
        // ViewConstructor：全部自定义 View 都由代码创建（无 XML inflate 路径），属误报。
        // GradleDependency：依赖升级走单独评审，不在 lint 里催更。
        disable += "SetTextI18n"
        disable += "ViewConstructor"
        disable += "GradleDependency"
        disable += "ObsoleteSdkInt"
        // AGP 8.7 + Kotlin 2.0's FIR lint analysis crashes on a few test-only Kotlin
        // classes. Unit/instrumentation compilation and execution still cover these sources.
        ignoreTestSources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // 仅测试用：android.util.Xml 在单元测试里是 stub，EPUB/DOCX/PPTX 解析器因此一直
    // 没有离线覆盖。kxml2 是 Android XmlPullParser 的上游实现，用它做垫片最接近真机。
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

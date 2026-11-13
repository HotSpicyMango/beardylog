import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.hsm.beardylog"
    compileSdk = 37

    // 서명 값은 local.properties에만 있다. 없는 PC(새로 클론, CI)에서도 디버그 빌드가 되도록 값이 있을 때만 만든다.
    val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.hsm.beardylog"
        minSdk = 28
        targetSdk = 37
        versionCode = 26
        versionName = "1.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 마이그레이션 테스트가 옛 버전 스키마를 불러올 수 있도록 Room이 버전별 스키마를 내보낸다.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
        buildConfigField("String", "KOREA_HOLIDAY_API_KEY", "\"${localProperties.getProperty("KOREA_HOLIDAY_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.window)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.material)
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    // room-testing(스키마 JSON 읽기)이 1.8.1 이상을 요구하는데 앱 런타임은 1.7.3으로 고정돼 충돌한다. 테스트가 앱 디버그 런타임 버전을 따르므로 디버그만 올린다.
    debugImplementation(libs.kotlinx.serialization.json)
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-analytics")
    // 베타 테스터가 겪은 크래시를 볼 방법이 없으면 무엇을 먼저 고쳐야 하는지 추측만 하게 된다.
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 공공데이터 키는 local.properties에만 둔다 (저장소에 올리지 않는다). 없으면 그 기능만 꺼진다.
//   evCharger.key=공공데이터포털 '한국환경공단_전기자동차 충전소 정보' 인증키
//   opinet.key=오피넷 무료 API 인증키
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun quotedProp(k: String): String =
    "\"" + (localProps.getProperty(k) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.akaloopang.evauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.akaloopang.evauto"
        // Pleos Connect 요구사항: minSdk 28 이상 (사이닝 키 로테이션·리니지 지원)
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "EV_CHARGER_KEY", quotedProp("evCharger.key"))
        buildConfigField("String", "OPINET_KEY", quotedProp("opinet.key"))
    }

    // AAOS 차량 API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 시연용 release APK — 디버그 키로 서명해 바로 설치·실행되게 한다.
            // 앱마켓에 실제로 올릴 때는 본인 키스토어로 바꾼다.
            signingConfig = signingConfigs.getByName("debug")
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
    lint {
        // 프로토타입 단계: 린트 경고로 release 빌드가 멈추지 않게
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))

    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // FusedLocation SDK: Pleos 문서상 "Not Available Yet"(저장소에 아직 없음) → 공개되면 추가하고 위치를 이 SDK로 바꾼다

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

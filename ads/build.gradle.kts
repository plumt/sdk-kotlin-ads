plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.ads"
    compileSdk = 36
    // 소비 앱의 리소스와 이름이 겹치지 않게 접두어를 강제한다
    resourcePrefix = "ads_"

    defaultConfig {
        // GMA Next-Gen SDK(ads-mobile-sdk)가 요구하는 최소값이다
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 은 API 레벨마다 android-all 을 올린다 — 여러 API 를 도는 테스트가 기본 힙(512m)을 넘는다
        unitTests.all { it.maxHeapSize = "1536m" }
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(DefaultImpls 없음) — 인터페이스에 함수를 더해도 구현체가 깨지지 않는다
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 공개 API 에 Flow 가 나온다
    api(libs.kotlinx.coroutines.android)
    // SDK 타입은 공개 API 에 드러내지 않는다. 앱 ID 는 앱 매니페스트에서 읽어 초기화 설정으로 넘긴다(UMP 도 같은 값을 읽는다).
    // 레거시 play-services-ads 는 의존하지 않는다 — Next-Gen 과 함께 들어오면 클래스가 겹친다(README "최소 요구사항")
    implementation(libs.ads.mobile.sdk)
    implementation(libs.user.messaging.platform)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )
    coordinates(artifactId = "ads", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("ads")
        description.set("GMA Next-Gen SDK(AdMob) 위의 동의(UMP)·초기화·전면 광고 빈도 제한·광고 끄기 스위치 계층 (비공식)")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
            // UMP SDK 직결 어댑터 — 실제 SDK 가 필요해 단위 테스트가 불가능하다(DefaultConsentManager 는 가짜 게이트웨이로,
            // 지역 매핑 toUmpGeography 는 MobileAdsSeamTest 가 검증하지만 같은 파일의 폼 오류 변환과 함께 파일 단위로 뺀다).
            // GMA Next-Gen SDK 직결 어댑터 — 실광고 SDK·네트워크가 필요하다. MobileAdsNetwork 의 초기화 순서·스레드·예외는
            // MobileAdsSdk 이음새로 검증하고, 정적 SDK 호출만 하는 RealMobileAdsSdk·공급자·네이티브 핸들은 뺀다
            classes(
                "net.plumt.ads.consent.UmpConsentGateway*",
                "net.plumt.ads.consent.ConsentGatewayKt*",
                "net.plumt.ads.mobileads.*Provider*",
                "net.plumt.ads.mobileads.MobileAdsNativeHandle*",
                "net.plumt.ads.mobileads.RealMobileAdsSdk*",
                "net.plumt.ads.mobileads.FullScreenProvidersKt*",
                "net.plumt.ads.mobileads.MainThreadEvents*",
                "net.plumt.ads.mobileads.MobileAdsBannerHandle*",
                "net.plumt.ads.mobileads.ErrorMappingKt",
            )
            }
        }
        verify {
            rule {
                // 라인 커버리지 90퍼센트 미만이면 koverVerify 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") { dependsOn("koverVerify") }

// 소비 앱이 광고 SDK 를 더 새 버전으로 올려도(Gradle 이 높은 쪽을 고른다) 이 라이브러리가 컴파일·동작하는지 확인하는 변형.
// 예: ./gradlew :ads:compileReleaseKotlin :ads:testDebugUnitTest -PgmaCompatVersion=1.6.0 (CI 가 알려진 최신 버전으로 돌린다)
providers.gradleProperty("gmaCompatVersion").orNull?.let { version ->
    configurations.configureEach {
        resolutionStrategy.force("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:$version")
    }
}

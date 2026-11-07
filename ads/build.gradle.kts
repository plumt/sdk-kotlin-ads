plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.ads"
    compileSdk = 36

    defaultConfig {
        // Google Mobile Ads SDK 와 UMP 가 요구하는 최소값이다
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()

}


dependencies {
    // 공개 API 에 Flow 가 나온다
    api(libs.kotlinx.coroutines.android)
    // SDK 타입은 공개 API 에 드러내지 않는다. 앱은 App ID 를 자기 매니페스트에 적으면 된다
    implementation(libs.play.services.ads)
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
        description.set("Google Mobile Ads SDK(AdMob) 위의 동의(UMP)·초기화·전면 광고 빈도 제한·광고 끄기 스위치 계층 (비공식)")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
            // UMP SDK 직결 어댑터 — 실제 SDK 가 필요해 단위 테스트가 불가능하다(DefaultConsentManager 는 가짜 게이트웨이로,
            // 지역 매핑 toUmpGeography 는 MobileAdsSeamTest 가 검증하지만 같은 파일의 폼 오류 변환과 함께 파일 단위로 뺀다).
            // Google Mobile Ads SDK 직결 어댑터 — 실광고 SDK·네트워크가 필요하다. MobileAdsNetwork 의 초기화 순서·스레드·예외는
            // MobileAdsSdk 이음새로 검증하고, 정적 SDK 호출만 하는 RealMobileAdsSdk·공급자·네이티브 핸들은 뺀다
            classes(
                "net.plumt.ads.consent.UmpConsentGateway*",
                "net.plumt.ads.consent.ConsentGatewayKt*",
                "net.plumt.ads.mobileads.*Provider*",
                "net.plumt.ads.mobileads.MobileAdsNativeHandle*",
                "net.plumt.ads.mobileads.RealMobileAdsSdk*",
                "net.plumt.ads.mobileads.FullScreenProvidersKt*",
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
// 예: ./gradlew :ads:compileReleaseKotlin :ads:testDebugUnitTest -PgmaCompatVersion=25.5.0 (CI 가 최신 버전으로 돌린다)
providers.gradleProperty("gmaCompatVersion").orNull?.let { version ->
    configurations.configureEach {
        resolutionStrategy.force("com.google.android.gms:play-services-ads:$version")
    }
}

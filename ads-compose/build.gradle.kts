plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.ads.compose"
    compileSdk = 36
    // 소비 앱의 리소스와 이름이 겹치지 않게 접두어를 강제한다
    resourcePrefix = "ads_"

    defaultConfig {
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
    explicitApi()
}

dependencies {
    api(project(":ads"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    // 기성 카드·종료 팝업의 기본 색·글꼴이 MaterialTheme 을 따른다. 색은 인자로 모두 바꿀 수 있다
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)

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
    coordinates(artifactId = "ads-compose", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("ads-compose")
        description.set("ads 모듈용 Compose 배너·네이티브 광고 슬롯·목록 광고 끼워넣기 도우미 (비공식)")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
            // Compose UI·AndroidView 접착 코드 — Compose UI 테스트(에뮬레이터)가 필요해 유닛 테스트에서 뺀다.
            // 순수 계산(InterleavePlan·풀 불러오기 루프·슬롯 판단)은 유닛 테스트한다. itemsWithAds 는 LazyListScope 접착이라 같이 뺀다
            classes(
                "net.plumt.ads.compose.AdBannerKt*",
                "net.plumt.ads.compose.NativeAdsKt*",
                "net.plumt.ads.compose.NativeAdViewIds",
                "net.plumt.ads.compose.AdsClientLocalKt*",
                "net.plumt.ads.compose.InterleaveKt*",
                "net.plumt.ads.compose.ComposableSingletons*",
                // 기성 카드·종료 팝업·미리보기 자리표시: Material3 그리기와 AndroidView 바인딩뿐이다.
                // 그리기 판단(slotDraw·canDrawNative·previewVisibility·배너 높이)은 순수 함수로 빼 유닛 테스트한다
                "net.plumt.ads.compose.NativeAdCardsKt*",
                "net.plumt.ads.compose.NativeAdDefaults",
                "net.plumt.ads.compose.NativeAdRowDefaults",
                "net.plumt.ads.compose.NativeAdMetrics",
                "net.plumt.ads.compose.ExitDialogKt*",
                "net.plumt.ads.compose.AdExitDialogDefaults",
                "net.plumt.ads.compose.AdPreviewKt*",
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

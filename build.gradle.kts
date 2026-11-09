plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // 발행하지 않는 확인용 앱 — release(R8) 빌드에서 의존 라이브러리의 리플렉션 대상이 남는지 본다
    ignoredProjects.add("sample")
    // Compose 컴파일러가 기본 슬롯 람다를 담으려 만드는 합성 클래스. 이름이 해시라 빌드마다 바뀔 수 있고 공개 계약이 아니다
    ignoredClasses.add("net.plumt.ads.compose.ComposableSingletons\$NativeAdCardsKt")
}

# sdk-kotlin-ads

Compose 앱을 위한 광고 연동 계층이다. 동의(UMP) 수집, SDK 초기화, 전면·보상형·앱 오프닝 광고의 미리 불러오기와 빈도 제한,
Compose 배너·네이티브 슬롯, 목록 광고 끼워넣기, "광고 끄기" 전역 스위치를 한 벌로 준다.

> **비공식 라이브러리다.** Google Mobile Ads SDK(AdMob)용으로 만들었지만 Google 과 제휴·후원·보증 관계가 없다.
> "AdMob"·"Google" 은 각 소유자의 상표다. 이 저장소는 광고 SDK 를 포함하지 않고 Maven 의존으로만 쓴다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `ads` | `net.plumt.ads:ads` | 설정·동의·초기화·빈도 정책·전면형 컨트롤러·네트워크 인터페이스와 기본 구현 |
| `ads-compose` | `net.plumt.ads:ads-compose` | `AdBanner`, 네이티브 슬롯, 목록 끼워넣기(`itemsWithAds`), `rememberFullScreenAdController` |

```kotlin
dependencies {
    implementation("net.plumt.ads:ads:0.0.1")
    implementation("net.plumt.ads:ads-compose:0.0.1")   // Compose 를 쓸 때
}
```

## 최소 요구사항

- minSdk 23(광고 SDK 와 UMP 가 요구하는 최소값), compileSdk 36, Java 11, Kotlin 2.2, Compose BOM 2025.02 이상
- 런타임 의존: `play-services-ads` 23.6.0, `user-messaging-platform` 4.0.0, `kotlinx-coroutines-android`, (`ads-compose`) Compose foundation·runtime·ui, `lifecycle-runtime-compose`
- 광고 SDK 타입은 `ads` 의 공개 API 에 나오지 않는다. 다른 네트워크로 바꾸려면 `AdNetwork` 를 구현한다

## 앱이 해야 할 일

1. **앱 ID 를 매니페스트에 적는다.** 이 라이브러리는 `APPLICATION_ID` meta-data 를 싣지 않는다 — 앱마다 값이 다르다.
   ```xml
   <application>
       <meta-data
           android:name="com.google.android.gms.ads.APPLICATION_ID"
           android:value="ca-app-pub-3940256099942544~3347511713" />  <!-- Google 공식 시험용 앱 ID -->
   </application>
   ```
   값이 없으면 SDK 가 초기화에서 실패하고, `AdsClient.initializationError` 에 `AdsException(Initialization)` 이 담긴다.
2. 광고 단위 ID 는 코드에서 넘긴다. 개발 중에는 Google 이 공개한 시험용 ID 만 쓴다(운영 ID 를 저장소에 커밋하지 않는다).
3. 아동 대상 앱이면 `AdAudience.forTarget(AdTarget.AllAges)` 를 설정하고, 매니페스트에서 광고 ID 권한을 지운다.
   ```xml
   <uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />
   ```

## 사용 예

```kotlin
// Application.onCreate — 앱에 하나만 둔다
val removeAds = MutableAdsEnabledSource(initial = true)   // 광고 제거 구매 시 set(false)
val ads = AdsClient.create(
    context = this,
    config = AdsConfig(audience = AdAudience.forTarget(AdTarget.Teen)),
    enabledSource = removeAds,
    events = AdEventListener { event -> analytics.log(event.toString()) },  // 분석 의존 없음
)
ads.initializeIfConsented()    // 저장된 동의가 있으면 UI 없이 초기화

// 첫 Activity — 동의 폼
lifecycleScope.launch {
    try { ads.consent.gather(this@MainActivity) } catch (e: AdsException) { /* 로그 */ }
}
```

```kotlin
setContent {
    ProvideAdsClient(ads) {
        val pool = rememberNativeAdPool(NATIVE_UNIT_ID, size = 3)
        LazyColumn {
            itemsWithAds(
                items = articles,
                interleave = AdInterleave(interval = 5, firstAdAfter = 3, maxAds = 3),
                adsEnabled = ads.adsAvailable.collectAsState().value,
                key = { it.id },
                adContent = { slot -> NativeAdSlot(pool, slot, R.layout.my_native, NativeAdViewIds(headline = R.id.headline, /* … */)) },
            ) { article -> ArticleRow(article) }
        }
        AdBanner(adUnitId = BANNER_UNIT_ID)
    }
}
```

시험용 단위 ID(Google 공개 값): 배너 `ca-app-pub-3940256099942544/9214589741`, 전면 `…/1033173712`,
보상형 `…/5224354917`, 앱 오프닝 `…/9257395921`, 네이티브 `…/2247696110`.

```kotlin
val interstitial = ads.fullScreenController(AdFormat.Interstitial, "level_end", INTERSTITIAL_UNIT_ID)
// 화면 전환 같은 자연스러운 휴지점에서만
when (interstitial.show(activity)) { ShowResult.Started -> … ; ShowResult.NotReady -> …; is ShowResult.Suppressed -> … }
// 화면을 떠날 때
interstitial.destroy()
```

## 정책과 모범 사례

- 라이브러리는 **광고를 자동으로 보이지 않는다.** 시작·종료 시점에 전면 광고를 띄우는 코드는 없고, `show` 는 호출부가 부른다
- 기본 빈도 정책(`IntervalFrequencyPolicy`)은 만든 지 30초 안, 마지막 표시 뒤 2분 안에는 막는다. 앱 오프닝 광고도 같은 유예가 적용돼 콜드 스타트 직후에는 나오지 않는다
- 보상형은 사용자가 요청하는 것이라 기본 `Unlimited` 다
- 광고 클릭을 유도·자동화하지 않는다. 네이티브 레이아웃의 "광고" 표시를 숨기지 않는다
- 동의 없이는 요청하지 않는다. 광고 제거 구매자에게는 동의 폼도 띄우지 않고 SDK 도 초기화하지 않는다
- 전면 광고는 작업이 끝난 자연스러운 지점에서, 사용자가 실수로 누르기 쉬운 위치(버튼 바로 뒤 등)를 피한다

## 확장 지점

| 인터페이스 | 용도 |
|---|---|
| `AdsEnabledSource` | 광고 끄기 스위치(결제·원격 설정 연결) |
| `AdFrequencyPolicy` | 표시 빈도 규칙. `AdFrequencyPolicy.all(...)` 로 조합, 저장소에 기록하는 정책도 직접 구현 |
| `AdClock` | 빈도·만료 판정의 시각 |
| `AdEventListener` | 분석 훅. 광고 소재·사용자 식별 정보 없음 |
| `AdNetwork` / `FullScreenAdProvider` / `BannerProvider` / `NativeAdProvider` | 다른 광고 네트워크·중개 계층으로 교체 |
| `ConsentManager` | 동의 흐름 대체(기본 UMP) |
| `PreloadPolicy` | 자동 미리 불러오기·재시도·만료 |

## 동작 규칙

- 광고 사용 가능 = 스위치 켜짐 + 동의가 요청 허용 + SDK 초기화 완료(`AdsClient.adsAvailable`). 스위치 값이 오기 전에는 꺼짐이다
- 스위치가 꺼지면 컨트롤러는 들고 있던 광고를 즉시 버리고, 배너·네이티브는 그리지 않으며 새 요청을 보내지 않는다
- Activity 는 `show` 호출 동안만 쓰고 붙들지 않는다. 배너는 컴포지션을 떠나면 destroy, 컨트롤러는 `destroy()`
- 모든 호출은 메인 스레드에서 한다

## 위협 모델

- 라이브러리는 광고 ID·사용자 식별자를 읽거나 저장하지 않는다. 분석 훅에는 형식·배치 이름·오류 코드·보상 종류/수량만 실린다
- 광고 단위 ID 는 비밀이 아니지만 운영 ID 를 공개 저장소에 올리면 부정 노출·클릭 유입으로 계정이 정지될 수 있다. 시험용 ID 로 개발한다
- 시험 기기 ID(`AdsConfig.testDeviceIds`)는 운영 빌드에 넣지 않는다. 동의 디버그 지역도 같다

## 테스트와 품질

`./gradlew build` 가 단위 테스트(Robolectric·가짜 네트워크, 실제 광고 요청 없음), `apiCheck`, `koverVerify`(라인 90% 이상)를 포함한다.
Kover 제외: 광고/UMP SDK 직결 어댑터(`mobileads` 의 SDK 호출부, `UmpConsentGateway`)와 Compose UI 접착 코드(`AdBanner`, 네이티브 슬롯, `itemsWithAds`)는
실제 SDK·UI 환경이 필요해 단위 테스트에서 뺐다. 순수 로직(정책·끼워넣기 계산·동의 상태 매핑·컨트롤러)은 전부 포함이다.

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `ads-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정한다.
오류와 실패 신호는 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 라이선스

[Apache License 2.0](LICENSE)

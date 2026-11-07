# sdk-kotlin-ads

Compose 앱을 위한 광고 연동 계층이다. 동의(UMP) 수집, SDK 초기화, 전면·보상형·앱 오프닝 광고의 미리 불러오기와 빈도 제한,
Compose 배너·네이티브 슬롯과 기성 카드·종료 팝업, 목록 광고 끼워넣기, "광고 끄기" 전역 스위치, 개발·운영 모드 구분을 한 벌로 준다.

> **비공식 라이브러리다.** Google Mobile Ads SDK(AdMob)용으로 만들었지만 Google 과 제휴·후원·보증 관계가 없다.
> "AdMob"·"Google" 은 각 소유자의 상표다. 이 저장소는 광고 SDK 를 포함하지 않고 Maven 의존으로만 쓴다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `ads` | `net.plumt.ads:ads` | 모드·광고 단위 등록부·설정·동의·초기화·빈도 정책·분류별 재시도·전면형 컨트롤러·네트워크 인터페이스와 기본 구현 |
| `ads-compose` | `net.plumt.ads:ads-compose` | `AdBanner`, 기성 네이티브 카드(`NativeAdRow`·`NativeAdListItem`·`NativeAdInline`), 종료 팝업, 네이티브 슬롯, 목록 끼워넣기, 미리보기 |

```kotlin
dependencies {
    implementation("net.plumt.ads:ads:0.0.2")
    implementation("net.plumt.ads:ads-compose:0.0.2")   // Compose 를 쓸 때
}
```

## 최소 요구사항

- minSdk 23(광고 SDK 와 UMP 가 요구하는 최소값), compileSdk 36, Java 11, Kotlin 2.2, Compose BOM 2025.02 이상
- 런타임 의존: `play-services-ads` 23.6.0, `user-messaging-platform` 4.0.0, `kotlinx-coroutines-android`, (`ads-compose`) Compose foundation·runtime·ui·material3, `lifecycle-runtime-compose`.
  material3 는 기성 카드·종료 팝업의 기본 색(`MaterialTheme`)에 쓴다. Material 3 를 쓰지 않는 앱은 색 인자를 모두 직접 넘긴다
- 광고 SDK 호환 범위: 23.6.0(컴파일 기준) ~ 25.5.0 에서 컴파일과 단위 테스트를 확인했다. 앱이 더 새 버전을 의존하면 Gradle 이 그 버전을 고른다.
  CI 는 `-PgmaCompatVersion=25.5.0` 으로 최신 버전 강제 해석 빌드를 함께 돌린다. 이 범위 밖은 보장하지 않는다
- 광고 SDK 타입은 `ads` 의 공개 API 에 나오지 않는다. 다른 네트워크로 바꾸려면 `AdNetwork` 를 구현한다
- R8(난독화·축소): 앱에 규칙을 더할 필요 없다. 광고 SDK 는 WorkManager 2.7 → Room 2.2 를 끌어오는데, 두 라이브러리가 싣는 규칙이
  R8 전체 모드(AGP 8 이상 기본)에서 리플렉션으로 만드는 클래스의 생성자를 지키지 못해 release 앱이 켜지자마자 죽는다
  (`Failed to create an instance of androidx.work.impl.WorkDatabase`). `ads` 의 consumer 규칙이 `RoomDatabase`·`InputMerger` 하위 클래스의
  기본 생성자를 지킨다. WorkManager·Room 버전은 고정하지 않는다 — 광고 SDK 25.5.0 도 WorkManager 2.7.0 을 끌어오고,
  WorkManager 2.9 로 올려도 Room 2.5 의 규칙이 같은 모양이라 고쳐지지 않으며, 쓰지 않는 의존 버전을 앱에 강요하게 된다.
  앱이 더 새 버전을 쓰면 그 규칙과 겹칠 뿐 해가 없다

## 앱이 해야 할 일

1. **앱 ID 를 매니페스트에 적는다.** 이 라이브러리는 `APPLICATION_ID` meta-data 를 싣지 않는다 — 앱마다 값이 다르다.
   빌드 유형별로 나누려면 `manifestPlaceholders` 를 쓴다. 개발 빌드는 Google 시험 앱 ID, 운영 빌드는 실제 앱 ID 다.
   ```kotlin
   // app/build.gradle.kts — 실제 값은 저장소 밖(로컬 설정·CI 비밀값)에서 읽는다
   android {
       buildTypes {
           debug   { manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713" } // Google 시험 앱 ID
           release { manifestPlaceholders["admobAppId"] = providers.gradleProperty("ADMOB_APP_ID").get() }
       }
   }
   ```
   ```xml
   <application>
       <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${admobAppId}" />
   </application>
   ```
   flavor(`dev`·`prod`)로 나누면 `productFlavors { getByName("prod") { manifestPlaceholders["admobAppId"] = … } }` 처럼 같은 키를 채운다.
   - meta-data 가 **아예 없으면** 광고 SDK 가 프로세스 시작 때 `IllegalStateException` 으로 앱을 멈춘다(라이브러리보다 먼저다). 광고를 끈 사용자도 똑같다
   - 값이 비었거나, **운영 모드인데 시험 앱 ID** 면 라이브러리가 감지해 SDK 를 초기화하지 않고 `configurationErrors` 로 알린다
     (운영 빌드가 시험 설정으로 나가는 것을 막는다). 개발 모드에서 실제 앱 ID 를 쓰는 것은 괜찮다 — 단위가 시험 ID 라 시험 광고만 온다
2. 광고 단위는 `AdUnit` 으로 한 곳에 모은다. 운영 ID 만 넘기면 개발 ID 는 형식별 Google 시험 ID 가 된다(운영 ID 를 공개 저장소에 커밋하지 않는다).
3. 아동 대상 앱이면 `AdAudience.forTarget(AdTarget.AllAges)` 를 설정하고, 매니페스트에서 광고 ID 권한을 지운다.
   ```xml
   <uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />
   ```

## 개발·운영 모드

앱은 단위마다 운영 ID 만 정하면 된다. 어느 ID 를 쓸지는 클라이언트가 모드로 고른다.

| | 개발(`Development`) | 운영(`Production`) |
|---|---|---|
| 광고 단위 ID | `AdUnit.development`(기본 Google 시험 ID) | `AdUnit.production` |
| `testDeviceIds`·`consentDebugGeography` | 적용 | 무시하고 `warnings` 와 로그 경고 |
| 운영 ID 가 비었거나 시험 ID | 상관없다 | 그 광고만 요청하지 않고 `configurationErrors`·`AdEvent.Misconfigured` |
| 매니페스트 앱 ID 가 시험 ID | 정상 | SDK 초기화 안 함, `configurationErrors` |

- 기본 `AdsMode.Auto` 는 **앱의** `ApplicationInfo.FLAG_DEBUGGABLE` 로 정한다(debug 빌드 유형 = 개발). 라이브러리의 `BuildConfig.DEBUG` 는
  앱의 debug/release 와 무관해 쓰지 않는다
- flavor 로 나누거나 디버그 가능한 운영 변형이 있으면 직접 넘긴다: `AdsConfig(mode = AdsMode.of(isProduction = BuildConfig.FLAVOR == "prod"))`
- 운영 모드의 설정 오류는 **던지지 않는다.** 운영 앱을 설정 실수 하나로 멈추지 않되, 시험 광고를 내보내지도, 조용히 숨기지도 않는다 —
  그 광고만 나가지 않고 원인이 `configurationErrors`·이벤트·로그(`PlumtAds`)에 남는다. 출시 전에는 앱 단위 테스트로 막는다:
  `assertTrue(AppAdUnits.registry.problemsFor(AdsMode.Production).isEmpty())`

## 사용 예

### 앱 설정 전체

```kotlin
// 광고 단위 — 앱에 한 곳. 운영 ID 는 BuildConfig 필드(로컬 설정·CI 비밀값에서 주입)
object AppAdUnits {
    val homeBanner   = AdUnit.banner("home_banner", BuildConfig.AD_HOME_BANNER)
    val detailBanner = AdUnit.banner("detail_banner", BuildConfig.AD_DETAIL_BANNER)
    val listNative   = AdUnit.native("list_native", BuildConfig.AD_LIST_NATIVE)
    val exitNative   = AdUnit.native("exit_native", BuildConfig.AD_EXIT_NATIVE)
    val levelEnd     = AdUnit.interstitial("level_end", BuildConfig.AD_LEVEL_END)
    val reward1      = AdUnit.rewarded("reward_hint", BuildConfig.AD_REWARD_HINT)
    val reward2      = AdUnit.rewarded("reward_hint_backup", BuildConfig.AD_REWARD_HINT_BACKUP)
    val registry = AdUnitRegistry(homeBanner, detailBanner, listNative, exitNative, levelEnd, reward1, reward2)
}

class App : Application() {
    lateinit var ads: AdsClient

    override fun onCreate() {
        super.onCreate()
        // 광고 끄기: 빌드 스위치(내부 빌드) + 광고 제거 구매. 모두 켜져 있을 때만 광고가 나간다
        val purchase = MutableAdsEnabledSource(initial = true)        // 구매 확인 뒤 set(false) — 즉시 반영된다
        val enabled = AdsEnabledSource.all(AdsEnabledSource.of(BuildConfig.ADS_ENABLED), purchase)

        ads = AdsClient.create(
            context = this,
            config = AdsConfig(
                mode = AdsMode.Auto,                                    // 앱 debug = 개발, release = 운영
                audience = AdAudience.forTarget(AdTarget.Teen),
                testDeviceIds = listOf("내 기기 해시"),                  // 개발 모드에서만 적용
            ),
            enabledSource = enabled,
            units = AppAdUnits.registry,                               // 운영 모드면 여기서 바로 검사
            events = AdEventListener { e -> analytics.log(e.toString()) },
        )
        ads.gatherConsentOnFirstResume(this) { result ->               // 첫 화면에서 한 번 동의 갱신·초기화
            result.exceptionOrNull()?.let { crashReporter.record(it) }
        }
        // 운영 빌드의 설정 실수를 원격으로 알린다
        ads.configurationErrors.value.forEach { crashReporter.record(it) }
    }
}
```

UMP 는 프로세스마다 `requestConsentInfoUpdate` 를 부르기 전에는 지난 실행의 동의가 있어도 `canRequestAds()` 가 false 다(공식 문서).
`gatherConsent`(그리고 `gatherConsentOnFirstResume`)는 갱신을 요청한 **직후** 저장된 동의로 바로 초기화한다. 직접 부르려면
첫 Activity 에서 `lifecycleScope.launch { ads.gatherConsent(this@MainActivity) }`. 스위치 첫 값이 늦게 와도 최대 5초 기다린다.

### 화면

```kotlin
setContent {
    ProvideAdsClient(ads) {
        Scaffold(bottomBar = { AdBanner(AppAdUnits.homeBanner) }) { padding ->
            val pool = rememberNativeAdPool(AppAdUnits.listNative, size = 3)
            LazyColumn(contentPadding = padding) {
                itemsWithAds(
                    items = articles,
                    interleave = AdInterleave(interval = 5, firstAdAfter = 3, maxAds = 3),
                    adsEnabled = rememberAdsAvailable(),
                    key = { it.id },
                    adContent = { slot -> NativeAdRow(pool, slot, style = NativeAdRowDefaults.style(minHeight = 72.dp)) },
                ) { article -> ArticleRow(article) }
            }
        }

        // 종료 확인 팝업: 시작 화면에서 미리 받아 두면 뜨는 순간 광고가 있다
        rememberExitAd(AppAdUnits.exitNative)
        var showExit by remember { mutableStateOf(false) }
        BackHandler { showExit = true }
        if (showExit) ExitConfirmDialog(AppAdUnits.exitNative, onDismiss = { showExit = false }, onExit = ::finish)
    }
}
```

기성 카드: `NativeAdRow`(한 줄, 미디어 없음 — 이미지 전용 단위), `NativeAdListItem`(목록 사이 큰 카드), `NativeAdInline`(팝업·바텀시트),
`NativeAdContent`(틀 없는 내용). 색은 `NativeAdColors`·`NativeAdRowStyle` 로 앱 디자인에 맞춘다. 직접 레이아웃을 쓰려면 `NativeAdSlot`.

### 전면·보상형

```kotlin
// 같은 형식의 단위 여러 개는 서로 독립이다. 빈도 제한은 형식별 공유(기본) 또는 단위별
val levelEnd = rememberFullScreenAdController(AppAdUnits.levelEnd)
val hint = rememberFullScreenAdController(
    AppAdUnits.reward1,
    fallbacks = listOf(AppAdUnits.reward2),          // 1번이 실패하면 2번으로
    frequencyScope = FrequencyScope.PerUnit,
)

when (val r = levelEnd.show(activity)) {
    ShowResult.Started -> Unit
    ShowResult.NotReady -> log("아직 없음: ${levelEnd.lastError.value?.reason}")
    is ShowResult.Suppressed -> Unit                   // 빈도·스위치·다른 광고 표시 중·설정 오류
    is ShowResult.Failed -> when (r.error.reason) {
        AdErrorReason.AppInBackground -> retryOnResume = true
        AdErrorReason.Expired, AdErrorReason.AlreadyUsed -> Unit   // 컨트롤러가 이미 새로 불러 둔다
        else -> log(r.error)
    }
}
```

보상형의 보상은 화면이 아니라 앱 수명에서 처리한다. 광고가 떠 있는 동안 화면이 회전·재생성되면 `show` 에 넘긴 콜백은
더 불리지 않는다. `AdsClient.create` 에 넘긴 `events` 리스너는 앱 수명이라 `AdEvent.RewardEarned` 를 놓치지 않는다.

### 실패 처리

| 분류(`AdErrorReason`) | 라이브러리가 하는 일 |
|---|---|
| `NoFill` | `PreloadPolicy.noFillBackoffMillis`(기본 1분)부터 두 배씩 길게 기다린다 |
| `Network` | 연결이 돌아오면 다시 시도한다(`AdConnectivity`). 대체 단위로 넘어가지 않는다. SDK 가 연결 실패를 내부 오류로 알리거나 오프라인 중에 난 `Internal`·`Timeout`·`Unknown` 도 여기로 분류한다 |
| `InvalidRequest`·`AppIdMissing`·`AppIdMismatch`·`Misconfigured` | 다시 요청하지 않는다 |
| `Internal`·`Timeout`·`Unknown` | `retryBackoffMillis`(기본 5초)부터 두 배씩. 오프라인이면 대기가 끝나도 연결이 돌아올 때까지 다시 요청하지 않는다 |
| 표시: `AlreadyUsed`·`Expired`·`AppInBackground` | `ShowResult.Failed` 로 알리고 새 광고를 불러 둔다 |

```kotlin
AdBanner(
    AppAdUnits.homeBanner,
    fallbacks = listOf(AppAdUnits.detailBanner),          // 첫 광고 전 실패면 다음 단위
    failure = AdSlotFailure.KeepSpace,                    // 자리 유지(기본). Collapse 면 접는다
    onLoadFailed = { e -> if (e.reason == AdErrorReason.InvalidRequest) crashReporter.record(e) },
)

val pool = rememberNativeAdPool(AppAdUnits.listNative, size = 2, retry = PreloadPolicy(maxRetries = 1))
NativeAdSlot(pool, 0, R.layout.my_native, ids, failure = AdSlotFailure.KeepSpace, placeholder = { Spacer(Modifier.height(72.dp)) })
when (val st = pool.state) { is AdSlotState.Failed -> log(st.error.reason); else -> Unit }
```

### 미리보기·스크린샷

Android Studio 프리뷰에서는 `ProvideAdsClient` 없이도 광고 컴포저블이 실제와 같은 크기의 자리표시를 그린다.
스크린샷 테스트는 `AdPreviewMode(adsVisible = true/false) { Screen() }` 으로 광고 있음/없음을 고른다. 클라이언트가 필요한 코드에는
`AdsClient.inert(context)`(아무것도 요청하지 않음)를 넘긴다. UI 테스트 태그는 `AdTestTags` 에 있다.

## 정책과 모범 사례

- 라이브러리는 **광고를 자동으로 보이지 않는다.** 시작·종료 시점에 전면 광고를 띄우는 코드는 없고, `show` 는 호출부가 부른다
- **빈도 상태는 클라이언트에 있다.** `fullScreenController(policy = null)`(기본)은 `ads.defaultPolicy(format)` 의 공유 인스턴스를 쓴다.
  화면 재진입·회전으로 컨트롤러가 다시 만들어져도 제한이 이어지고, 마지막 표시 시각은 앱 전용 SharedPreferences
  (`AdFrequencyStore.sharedPreferences`, 파일 `net.plumt.ads.frequency`)에 남아 프로세스 재시작도 넘는다.
  정책을 직접 넘길 때는 앱에 하나 두고 공유한다 — 화면마다 새로 만들면 그때마다 제한이 초기화된다
- 전면 기본 정책: 클라이언트를 만든 지 30초 안, 마지막 표시 뒤 2분 안에는 막는다. 기기 시계가 뒤로 가면 그 시각부터 다시 센다
- 앱 오프닝 기본 정책: 시작 유예 없이(콜드 스타트 로딩 화면에 쓸 수 있다) 2분 간격, 그리고 **첫 실행은 보이지 않는다**(`LaunchCountFrequencyPolicy`).
  세 값 모두 `AdsConfig` 로 바꾼다: `appOpenMinIntervalMillis`(기본 120000), `appOpenSkipFirstLaunches`(기본 1, 0 이면 첫 실행부터),
  `appOpenCooldownMillis`(기본 60000). 규칙 자체를 바꾸려면 `fullScreenController(policy = …)` 에 앱 전역 정책을 넘긴다
- 전면형 광고는 한 클라이언트에서 한 번에 하나만 보인다(`SuppressReason.AnotherAdShowing`). 앱 오프닝은 전면·보상형이 닫힌 뒤
  `AdsConfig.appOpenCooldownMillis`(기본 60초) 동안 막혀, 광고 클릭 뒤 복귀 때 연달아 뜨지 않는다
- 보상형은 사용자가 요청하는 것이라 기본 `Unlimited` 다
- 배너는 기본으로 광고 높이만큼 자리를 미리 잡는다(`AdBanner(reserveSpace = true)`) — 광고가 도착하며 콘텐츠가 밀려 실수 클릭이
  나는 것을 막는다. 한 번 광고가 오면 갱신이 실패해도 높이를 유지한다. 폭은 화면이 아니라 부모가 준 실제 폭으로 정한다
- 네이티브: 동영상 광고는 미디어 뷰(`MediaView`) 없이 보일 수 없어, 레이아웃에 미디어 뷰가 없으면 `NativeAdSlot` 이 그 광고를
  그리지 않는다. 미디어 뷰는 가로·세로 120dp 이상, AdChoices 아이콘 자리(오른쪽 위)에는 다른 뷰를 겹치지 않는다.
  풀은 1시간(`refreshAfterMillis`)마다 새로 받는다
- 광고 클릭을 유도·자동화하지 않는다. 네이티브 레이아웃의 "광고" 표시를 숨기지 않는다
- 동의 없이는 요청하지 않는다. 광고 제거 구매자에게는 동의 폼도 띄우지 않고 SDK 도 초기화하지 않는다
- 전면 광고는 작업이 끝난 자연스러운 지점에서, 사용자가 실수로 누르기 쉬운 위치(버튼 바로 뒤 등)를 피한다

## 확장 지점

| 인터페이스 | 용도 |
|---|---|
| `AdsEnabledSource` | 광고 끄기 스위치(결제·원격 설정 연결). `Always`·`Never`·`of(Boolean)`·`from(Flow)`·`all(...)` |
| `AdUnit` / `AdUnitRegistry` | 이름 붙은 광고 단위(운영·개발 ID)와 등록부 |
| `AdConnectivity` | 연결 상태(네트워크 오류 재시도가 복구를 기다리고, 오프라인이 되면 배너 뷰를 없애 자동 갱신을 막고 복구 때 새로 만든다). 기본 `system(context)` — 인터넷 능력이 있고 시스템이 연결을 확인(`VALIDATED`)한 망이 있을 때 연결로 본다 |
| `AdFrequencyPolicy` | 표시 빈도 규칙. `AdFrequencyPolicy.all(...)` 로 조합. 기본 구현 `IntervalFrequencyPolicy`·`LaunchCountFrequencyPolicy` |
| `AdFrequencyStore` | 빈도 기록 저장소. 기본 `sharedPreferences(context)`(영속)·`inMemory()`. DataStore 등으로 교체 |
| `AdClock` | 빈도·만료 판정의 시각(`Default` 단조 시계, `Wall` 기기 시계) |
| `AdEventListener` | 분석 훅. 광고 소재·사용자 식별 정보 없음 |
| `AdNetwork` / `FullScreenAdProvider` / `BannerProvider` / `NativeAdProvider` | 다른 광고 네트워크·중개 계층으로 교체 |
| `ConsentManager` | 동의 흐름 대체(기본 UMP) |
| `PreloadPolicy` | 자동 미리 불러오기·분류별 재시도·시간 제한·만료 |
| `NativeAdColors` / `NativeAdRowStyle` | 기성 카드 색·모양 |

## 동작 규칙

- 광고 사용 가능 = 스위치 켜짐 + 동의가 요청 허용 + SDK 초기화 완료(`AdsClient.adsAvailable`). 스위치 값이 오기 전에는 꺼짐이다.
  스위치 Flow 가 예외로 끝나면 꺼짐으로 닫고 원인을 `enabledSourceError` 로 알린다
- SDK 초기화(`MobileAds.initialize`)는 백그라운드(IO) 스레드에서 한다. 실패는 `initializationError` 에 담기고
  동의·스위치 변화, `initializeIfConsented`, `gatherConsent` 때 다시 시도한다
- 스위치가 꺼지면 컨트롤러는 들고 있던 광고를 즉시 버리고, 배너·네이티브는 그리지 않으며 새 요청을 보내지 않는다
- Activity 는 `show` 호출 동안만 쓰고 붙들지 않는다. 배너는 컴포지션을 떠나면 destroy, 컨트롤러는 `destroy()`
- 모든 호출은 메인 스레드에서 한다

## 중개(mediation) 제한

기본 구현(`MobileAdsNetwork`)은 AdMob 단독 기준이다. 전면형·네이티브 로더에 애플리케이션 컨텍스트를 넘기는데, 일부 중개 어댑터는
Activity 컨텍스트를 요구한다. 네이티브 풀의 여러 개 한 번에 요청(`loadAds(count)`)도 중개에서는 지원되지 않는다.
중개를 쓰려면 `AdNetwork`(또는 해당 공급자)를 직접 구현해 넘기고, 네이티브 풀 크기는 1 로 둔다.

## 위협 모델

- 라이브러리는 광고 ID·사용자 식별자를 읽거나 저장하지 않는다. 분석 훅에는 형식·배치 이름·오류 코드·보상 종류/수량만 실린다
- 광고 단위 ID 는 비밀이 아니지만 운영 ID 를 공개 저장소에 올리면 부정 노출·클릭 유입으로 계정이 정지될 수 있다. 시험용 ID 로 개발한다
- 시험 기기 ID(`AdsConfig.testDeviceIds`)·동의 디버그 지역은 개발 모드에서만 적용된다. 운영 모드에서는 무시하고 경고한다
- 운영 모드에서 시험 단위 ID·시험 앱 ID 가 섞이면 그 광고(앱 ID 면 전체)를 요청하지 않는다 — 운영 빌드가 시험 광고를 내보내지 않는다

## 테스트와 품질

`./gradlew build` 가 단위 테스트(Robolectric·가짜 네트워크, 실제 광고 요청 없음), `apiCheck`, `koverVerify`(라인 90% 이상)를 포함한다.
Kover 제외: 광고/UMP SDK 정적 호출만 하는 어댑터(`RealMobileAdsSdk`, 전면형·배너·네이티브 공급자, 네이티브 핸들, `UmpConsentGateway`)와
Compose UI 접착 코드(`AdBanner`, 네이티브 슬롯·기성 카드·종료 팝업·미리보기 자리표시, `itemsWithAds`)는 실제 SDK·UI 환경이 필요해 단위 테스트에서 뺐다.
그 안의 판단은 순수 함수로 빼서 검증한다: 초기화 순서·스레드(`MobileAdsSdk` 이음새), 취소 경합 해제, 동의 지역 매핑,
동영상 네이티브 거부, 배너 폭·높이·대체 단위 판단, 슬롯 실패 처리, 네이티브 풀 불러오기·재시도 루프, 미리보기 판단, 오류 코드 분류. 순수 로직(정책·저장소·끼워넣기 계산·컨트롤러·조율)은 전부 포함이다.
SDK 콜백 순서와 실제 뷰 바인딩은 시험 광고 단위로 실기기에서 확인해야 한다.
`sample/` 은 발행하지 않는 확인용 앱이다. `check` 가 R8 전체 모드 release APK 를 만들고 `verifyReleaseKeptConstructors` 로 dex 를 열어
`WorkDatabase_Impl`·`OverwritingInputMerger`·`ArrayCreatingInputMerger` 의 생성자가 남았는지 기기 없이 확인한다(CI 는 최신 광고 SDK 로도 돌린다).

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `ads-v0.0.2`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정한다.
오류와 실패 신호는 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 라이선스

[Apache License 2.0](LICENSE)

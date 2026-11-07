# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## ads

### [0.0.2] - 2026-11-07

0.0.1 은 발행되지 않았으므로 호환을 깨는 변경을 이 버전에 모았다.

#### 추가
- 개발·운영 모드: `AdsConfig.mode`(`AdsMode.Auto`·`Development`·`Production`, `AdsMode.of`), `AdsClient.mode`.
  `Auto` 는 앱의 `ApplicationInfo.FLAG_DEBUGGABLE` 로 정한다(라이브러리 `BuildConfig.DEBUG` 는 쓰지 않는다)
- 이름 붙은 광고 단위와 등록부: `AdUnit`(이름·형식·운영 ID·개발 ID, 개발 ID 기본은 형식별 Google 시험 ID), `AdUnit.banner`·`native`·
  `interstitial`·`rewarded`·`appOpen`, `idFor`·`problemFor`·`requireFormat`, `AdUnitRegistry`, `TestAdIds`
- 설정 오류 신호: `AdsErrorKind.Configuration`, `AdsClient.configurationErrors`, `AdEvent.Misconfigured`, `SuppressReason.Misconfigured`,
  `FullScreenAdController.misconfigured`, `AdsClient.adUnitId`. 운영 모드에서 운영 ID 가 비었거나 시험 ID 면 그 광고를 요청하지 않는다.
  `create(units = …)` 면 만들 때 등록부 전체를 검사한다
- 매니페스트 앱 ID 검사: 없거나 비면 두 모드 모두, 운영 모드에 시험 앱 ID 면 초기화하지 않고 `Configuration` 실패
- `AdsWarning`·`AdsClient.warnings`: 운영 모드에서 `testDeviceIds`·`consentDebugGeography` 를 무시하고 알린다(개발 모드에서만 적용)
- 실패 분류 `AdErrorReason`(`NoFill`·`Network`·`InvalidRequest`·`AppIdMissing`·`AppIdMismatch`·`Misconfigured`·`Internal`·`Timeout`·
  `AlreadyUsed`·`Expired`·`AppInBackground`·`Unknown`)과 `AdsException.reason`, `AdEvent.LoadFailed/ShowFailed.reason`
- 분류별 재시도: `PreloadPolicy.noFillBackoffMillis`·`retryOnNetworkRestore`·`loadTimeoutMillis`, `RetryStep`, `PreloadPolicy.nextStep`.
  네트워크 오류는 연결 복구를 기다린다(`AdConnectivity`, 기본 `AdConnectivity.system`)
- 대체 단위: `fullScreenController(fallbacks = …)`, `FullScreenAdController.loadedUnit`·`lastError`
- 단위별·형식별 빈도: `FrequencyScope`, `defaultPolicy(unit, scope)`, `fullScreenController(frequencyScope = …)`
- 앱 수명 네이티브 캐시 `PrefetchedNativeAd`·`AdsClient.prefetchedNativeAd`(종료 팝업용, TTL·소비·대체 단위)
- `AdsClient.gatherConsentOnFirstResume`(첫 화면에서 한 번 동의 수집), `AdsEnabledSource.Never`·`of`·`all`
- `AdsClient.inert`(미리보기·시험용 무동작 클라이언트), `NativeAdProvider.createMediaView`
- `AdsException.CODE_PRODUCTION_ID_MISSING`·`CODE_TEST_ID_IN_PRODUCTION`·`CODE_APP_ID_MISSING`·`CODE_TEST_APP_ID_IN_PRODUCTION`·`CODE_TIMEOUT`·`CODE_EXPIRED`

- `AdsException.withReason`, `AdsException.classifyOffline(online)`, `PreloadPolicy.awaitRetry(reason, retriesDone, online)`

#### 수정(실기기 검증)
- 없는 단위의 네이티브 요청이 끝나지 않던 문제. 실패 콜백 시점에 로더가 아직 불러오는 중이라고 답하면 다시 불리지 않아
  영원히 기다렸다. 이제 실패 콜백은 늘 요청을 끝낸다(받은 광고가 있으면 그것으로). `PrefetchedNativeAd` 도 불러오기 한 번에
  30초 제한을 두어 응답이 없으면 대체 단위로 넘어간다
- 오프라인 실패가 `Network` 가 아니라 `Internal`(코드 0, 호스트 이름 조회 실패)로 분류되던 문제. SDK 메시지가 연결 실패면
  `Network` 로 보고, 연결이 끊긴 동안 난 `Internal`·`Timeout`·`Unknown` 도 `Network` 로 고친다
- 오프라인 동안 짧은 간격으로 재요청하던 문제. `retryOnNetworkRestore`(기본) 면 대기가 끝나도 연결이 돌아올 때까지 다시 요청하지 않고,
  네트워크 실패면 대체 단위로 넘어가지 않는다
- `AdConnectivity.system` 이 비행기 모드 전환을 놓치던 문제. 망을 잃은 시점에 기본 망을 다시 묻지 않고 망별 상태로 판정한다.
  인터넷 능력에 더해 시스템이 연결을 확인한(`VALIDATED`) 망만 연결로 본다

- release(R8 전체 모드) 앱이 켜지자마자 죽던 문제(`Failed to create an instance of androidx.work.impl.WorkDatabase`).
  광고 SDK 가 끌어오는 Room 2.2·WorkManager 2.7 의 keep 규칙이 생성자를 지키지 않았다. consumer 규칙에
  `RoomDatabase`·`InputMerger` 하위 클래스의 기본 생성자 keep 을 넣었다(뒤쪽은 생성자가 지워진 것을 dex 로 확인했고, 실패는 WorkManager 코드 경로로 판단한 예방이다. 실기기에서 관측하지는 않았다).
  확인용 앱 `sample` 의 release dex 검사(`verifyReleaseKeptConstructors`)가 `check` 에서 막는다

#### 변경(호환 깨짐)
- `fullScreenController(format, placement, adUnitId, …)` → `fullScreenController(unit: AdUnit, placement = unit.name, …)`. 형식은 단위에서 온다
- `AdsException` 메시지 머리가 `[kind/reason/code]` 가 됐다
- 들고 있던 전면형 광고가 만료돼 `show` 가 버린 경우 `ShowResult.NotReady` 대신 `ShowResult.Failed(reason = Expired)` 를 돌려준다
- 불러오기 한 번에 기본 30초 제한(`loadTimeoutMillis`)이 생겼다. 0 이면 이전처럼 제한 없음
- `AdNetwork.initialize` 가 받는 설정은 모드를 정한 뒤의 값이다(운영 모드면 개발 전용 설정이 지워져 있다)
- `AdsClient.create` 에 `units`·`connectivity` 인자가 생겼다

### [0.0.1] - 발행 안 함

#### 추가
- `AdsClient` 진입점(`create`), `AdsConfig`, `AdAudience`/`AdTarget`
- 동의(UMP): `ConsentManager`, `ConsentSnapshot`, `ConsentStatus`
- `AdsEnabledSource`(광고 끄기 스위치), `MutableAdsEnabledSource`
- 전면·보상형·앱 오프닝: `FullScreenAdController`, `PreloadPolicy`, `AdFrequencyPolicy`, `IntervalFrequencyPolicy`, `AdClock`
- `AdEvent`/`AdEventListener` 분석 훅, `AdsException`
- 교체 가능한 `AdNetwork` 와 기본 구현 `MobileAdsNetwork`
- `AdFrequencyStore`(기본 `sharedPreferences`·`inMemory`), `LaunchCountFrequencyPolicy`, `AdClock.Wall`
- `AdsClient.defaultPolicy`(형식별 공유 정책), `enabledSourceError`, `ConsentGatherResult`
- `SuppressReason.AnotherAdShowing`, `ShowResult.Failed`, `AdsErrorKind.EnabledSource`, `AdsConfig.appOpenCooldownMillis`·`appOpenMinIntervalMillis`·`appOpenSkipFirstLaunches`
- `ConsentDebugGeography.RegulatedUsState`, `NativeAdAssets.hasVideo`

#### 변경 (공개 전 리뷰 반영)
- 빈도 제한 상태를 클라이언트로 옮겼다. 기본 정책은 형식별 공유 인스턴스이고 마지막 표시 시각은 영속 저장소에 남는다 —
  컨트롤러 재생성(화면 재진입·회전)과 프로세스 재시작에도 제한이 이어진다. `AdsClient.defaultPolicyFor` 는 없앴다
- `fullScreenController`·`rememberFullScreenAdController` 의 `policy` 기본값이 null(= `defaultPolicy`)이 됐다
- `gatherConsent` 가 스위치 첫 값을 기다리고(기본 5초) 결과를 `ConsentGatherResult` 로 돌려준다. 끝에 초기화를 다시 시도한다
- `initializeIfConsented` 도 지난 초기화 실패를 다시 시도한다

#### 실기기 검증 반영
- 수정: 동의를 저장한 뒤 콜드 스타트하면 광고가 켜지지 않던 문제. UMP 는 프로세스마다 `requestConsentInfoUpdate` 를 부르기 전에는
  `canRequestAds()` 가 늘 false 인데, `initializeIfConsented` 는 갱신을 요청하지 않고 저장값만 읽었다. 이제 `gatherConsent` 가 갱신을
  요청한 직후(응답·폼 전) 상태를 다시 읽어 지난 실행의 동의가 허용이면 바로 초기화한다(공식 권장의 병행 초기화).
  `initializeIfConsented` 단독으로는 콜드 스타트에 초기화되지 않는다는 점을 KDoc·README 에 적었다. 디버그 지역과 무관하다
- `MobileAds.initialize` 를 IO 스레드에서 부른다
- 앱 오프닝 기본 정책: 시작 유예 없음 + 첫 실행 제외. 전면형 동시 표시 금지, 다른 전면형이 닫힌 뒤 앱 오프닝 대기
- `IntervalFrequencyPolicy` 기본 시계가 `AdClock.Wall` 이 됐고, 시계가 뒤로 가면 그 시각부터 다시 센다
- `NativeAdHandle.bind` 가 `Boolean` 을 돌려준다(동영상 광고를 미디어 뷰 없이 묶지 않는다)
- 동의 시험 지역 `NotEea` 가 UMP 의 `DEBUG_GEOGRAPHY_OTHER` 를 쓴다
- `consumer-rules.pro` 의 `-dontwarn` 을 지웠다

#### 수정
- 교체한 네트워크 초기화·스위치 Flow 의 일반 예외가 메인 스레드 크래시로 번지던 문제
- 표시 호출 안에서 곧바로 실패해도 `ShowResult.Started` 를 돌려주던 문제
- 재시도 대기가 두 배씩 늘다 넘쳐 음수가 되면 요청이 폭주하던 문제
- 표시 중 컨트롤러가 destroy 되면(화면 재생성) 사라진 화면의 콜백을 계속 부르던 문제
- 취소와 재개가 겹치면 불러온 전면형·네이티브 광고를 놓지 않던 문제
- 네이티브 별점 값을 넣지 않던 문제, 다시 묶을 때 이전 `NativeAdView` 를 destroy 하지 않던 문제

## ads-compose

### [0.0.2] - 2026-11-07

#### 추가
- 기성 네이티브 카드(레이아웃 XML 없이): `NativeAdRow`(+`NativeAdRowStyle`·`NativeAdRowDefaults`), `NativeAdListItem`, `NativeAdInline`,
  `NativeAdContent`, `NativeAdColors`·`NativeAdDefaults`. "광고" 배지를 늘 그리고, 영어·한국어 문자열을 싣는다
- 종료 확인 팝업 `ExitConfirmDialog`(단위·미리 받은 광고 두 형태)와 `rememberExitAd`(앱 수명 캐시, 1시간 TTL)
- `rememberNativeAd`, `rememberAdsAvailable`
- 미리보기: `AdPreviewMode`·`LocalAdPreview`·`AdPreviewConfig`. Android Studio 프리뷰와 `AdPreviewMode` 안에서는 클라이언트 없이 자리표시를 그린다
- 실패 처리: `AdSlotFailure`(`Collapse`·`KeepSpace`), `AdSlotState`, `NativeAdPool.state`, `AdBanner(failure, fallbacks, retry, onLoadFailed)`,
  `rememberNativeAdPool(fallbacks, retry, onLoadFailed)`, `NativeAdSlot(failure, placeholder)`
- 자동화 태그 `AdTestTags`

#### 수정(실기기 검증)
- 네이티브 풀이 `Loading` 에 머물러 대체 단위로 넘어가지 않던 문제. 단위마다 `retry.loadTimeoutMillis`(기본 30초) 제한을 둔다
- 오프라인 동안 배너가 약 5초마다 다시 요청하던 문제. 연결이 끊긴 채 난 실패는 `Network` 로 보고 연결 복구를 기다린다.
  배너는 오프라인 동안 자동 갱신을 멈추고(pause) 연결이 돌아오면 다시 돌린다. 네이티브 풀·배너 모두 네트워크 실패면 대체 단위로 넘어가지 않는다
- 오프라인 동안 배너 자동 갱신이 멈추지 않고 60초마다 요청하던 문제. 연결이 바뀌어도 pause 를 바로 걸지 않았고, 멈춘 뒤 요청·응답이
  갱신을 다시 돌렸다. 요청 뒤에 멈춤을 걸고 요청·응답 직후 다시 건다
- 위 수정 뒤에도 오프라인 배너가 60초마다 갱신을 요청하던 문제. 광고 SDK 의 `pause` 는 요청 전이면 무시되고, 요청 뒤에는
  SDK 내부 원격 구현에 넘길 뿐이라 갱신 타이머가 멈춘다는 보장이 없었다. 이제 오프라인이 되면 배너 뷰를 destroy 하고 슬롯에서
  떼며, 연결이 돌아오면 새 뷰를 한 번 만들어 요청한다. 오프라인으로 시작하면 연결될 때까지 뷰를 만들지 않는다.
  뗀 동안 자리는 `failure` 를 따른다(`Collapse` 는 접고, `KeepSpace` 는 광고를 보였거나 `reserveSpace` 면 광고 높이를 빈 자리로 둔다).
  수명주기 resume 은 오프라인 상태를 덮지 않는다

#### 변경(호환 깨짐)
- `AdBanner`·`rememberNativeAdPool`·`rememberFullScreenAdController` 가 ID 문자열 대신 `AdUnit` 을 받는다. 형식이 다른 단위는 즉시
  `IllegalArgumentException`, 모드와 맞지 않는 단위는 던지지 않고 그리지 않는다
- `LocalAdsClient` 가 `AdsClient?`(기본 null)가 됐다 — 공급자 없는 프리뷰가 죽지 않는다. 실제 화면에서 없으면 여전히 `IllegalStateException`
- 네이티브 풀이 실패 뒤 분류별로 다시 시도한다(이전에는 멈췄다)
- `Compose material3` 를 의존한다(기성 카드·팝업의 기본 색)

### [0.0.1] - 발행 안 함

#### 추가
- `ProvideAdsClient`, `AdBanner`(앵커드 적응형), `rememberNativeAdPool`/`NativeAdSlot`
- `AdInterleave`/`InterleavePlan`/`itemsWithAds` 목록 광고 끼워넣기
- `rememberFullScreenAdController`

#### 변경 (공개 전 리뷰 반영)
- `AdBanner` 가 화면 폭 대신 부모가 준 실제 폭으로 크기를 정한다. `reserveSpace`(기본 true)로 처음부터 자리를 잡고,
  한 번 광고가 오면 갱신이 실패해도 높이를 유지한다
- `NativeAdSlot` 이 미디어 뷰 없는 레이아웃에 동영상 광고를 그리지 않는다. 뷰 태그 키를 라이브러리 리소스 id 로 바꿨다
- `rememberNativeAdPool` 이 `refreshAfterMillis`(기본 1시간)마다 새로 받는다

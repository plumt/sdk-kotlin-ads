# 오류와 실패 신호

이 라이브러리의 실패 타입은 `AdsException` 하나다(`kind`·`reason`·`code`·`cause`). 틀린 인자는 `IllegalArgumentException`, 잘못된 상태는 `IllegalStateException` 이다.
메시지 문구는 바뀔 수 있으니 분기는 예외 타입과 `kind`·`reason` 으로 한다. `code` 는 네트워크마다 뜻이 달라 기록용이다.

## 원인 분류 AdErrorReason

광고 SDK 오류 코드를 옮긴 값이다. 재시도는 이 값으로 정한다(`PreloadPolicy.nextStep`·`awaitRetry`). 모르는 코드는 `Unknown`.
SDK 는 오프라인을 불러오기 0(내부 오류)과 "Unable to resolve host" 같은 메시지로 알리기도 한다 — 그 메시지와,
연결이 끊긴 동안(`AdConnectivity`) 난 `Internal`·`Timeout`·`Unknown` 은 `Network` 로 분류한다(`AdsException.classifyOffline`).

| reason | 다시 시도 | 기본 SDK 코드 | 라이브러리 기본 동작 |
|---|---|---|---|
| `NoFill` | 예 | 불러오기 3(채울 광고 없음)·9(중개 채움 없음) | `noFillBackoffMillis`(기본 1분)부터 두 배씩 기다린다 |
| `Network` | 예 | 불러오기 2, 연결 실패 메시지의 0, 오프라인 중의 내부·시간 초과·모름 | `retryOnNetworkRestore`(기본 켜짐)면 연결이 돌아온 직후, 아니면 보통 간격. 대체 단위로 넘어가지 않는다 |
| `InvalidRequest` | 아니오 | 불러오기 1·11 | 다시 요청하지 않는다. 단위 ID 를 고친다 |
| `AppIdMissing` | 아니오 | 불러오기 8, 라이브러리 `CODE_APP_ID_MISSING` | 다시 요청하지 않는다 |
| `AppIdMismatch` | 아니오 | 불러오기 10, 라이브러리 `CODE_TEST_APP_ID_IN_PRODUCTION` | 다시 요청하지 않는다 |
| `Misconfigured` | 아니오 | 라이브러리 `CODE_PRODUCTION_ID_MISSING`·`CODE_TEST_ID_IN_PRODUCTION` | 요청 자체를 보내지 않는다 |
| `Internal` | 예 | 불러오기 0, 표시 0·4 | `retryBackoffMillis`(기본 5초)부터 두 배씩 |
| `Timeout` | 예 | 라이브러리 `CODE_TIMEOUT`(`loadTimeoutMillis` 초과. 네이티브 풀·`PrefetchedNativeAd` 포함) | 다음 대체 단위, 다 쓰면 보통 간격 |
| `AlreadyUsed` | 예 | 표시 1(이미 쓴 광고) | 새 광고를 불러 둔다 |
| `Expired` | 예 | 표시 2(준비 안 됨), 라이브러리 `CODE_EXPIRED`(`expireAfterMillis` 초과) | 새 광고를 불러 둔다 |
| `AppInBackground` | 예 | 표시 3(앱이 전면에 없음) | 새 광고를 불러 두고, 앱이 돌아온 뒤 호출부가 다시 보인다 |
| `Unknown` | 예 | 그 밖 | 보통 간격 |

재시도 횟수 상한은 `PreloadPolicy.maxRetries`(기본 2)다. 대체 단위가 있으면 한 번의 시도가 주 단위 → 대체 단위 순으로 돌고, 마지막 실패의 분류로 다음을 정한다.
배너는 첫 광고 전 실패에만 대체·재시도를 하고, 한 번 광고가 온 뒤의 갱신 실패는 광고 SDK 의 자동 갱신에 맡긴다.
오프라인이 되면 배너는 뷰를 destroy 하고 슬롯에서 떼어(자리는 `AdSlotFailure` 를 따른다) 요청을 보내지 않고, 연결이 돌아오면 새 뷰로 한 번 요청한다. 그 새 뷰는 첫 광고 전 상태로 다시 센다.

## AdsException

| kind | 언제 | 어디서 나오나 | 호출부가 할 일 |
|---|---|---|---|
| `Consent` | 동의 정보 갱신·동의 폼·개인정보 옵션 폼 실패(`code` 는 UMP `FormError.ErrorCode`) | `ConsentManager.gather`·`showPrivacyOptions`·`AdsClient.gatherConsent` 가 던진다 | 오프라인(`INTERNET_ERROR`)은 정상 상태로 보고 재시도한다. 지난 실행의 동의가 요청을 허용하면 광고는 계속 쓸 수 있다 |
| `Initialization` | SDK 초기화 실패. 교체한 `AdNetwork.initialize` 가 `AdsException` 이 아닌 예외를 던지면 `CODE_UNKNOWN` 으로 감싸 `cause` 를 보존한다 | 던지지 않고 `AdsClient.initializationError` 에 담긴다. 광고는 쓸 수 없는 상태로 남고 동의·스위치 변화, `initializeIfConsented`, `gatherConsent` 때 다시 시도한다 | 원인을 기록하고 다음 앱 시작이나 동의 수집 때 재시도에 맡긴다 |
| `EnabledSource` | 앱이 꽂은 `AdsEnabledSource.enabled` Flow 가 예외로 끝났다 | 던지지 않고 `AdsClient.enabledSourceError` 에 담긴다. 스위치는 꺼짐(광고 없음)으로 닫힌다 | 스위치 Flow 를 고친다. 다시 켜려면 클라이언트를 새로 만든다 |
| `Load` | 광고 불러오기 실패, 배너 뷰 생성 실패, 시간 초과. 채울 광고 없음(`NoFill`)은 정상 운영 상태 | 컨트롤러·`AdBanner`·네이티브 풀·`PrefetchedNativeAd` 가 `AdEvent.LoadFailed(reason)` 로 알린다(던지지 않음). 상태는 `FullScreenAdController.lastError`·`NativeAdPool.state`·`AdBanner(onLoadFailed)`. `AdNetwork` 공급자는 던진다 | 분석 훅으로 집계한다. 재시도는 위 분류표대로 라이브러리가 한다 |
| `Show` | 광고 표시 실패(이미 쓴 광고·만료·앱이 백그라운드 등, `reason` 으로 구분) | `ShowResult.Failed`·`FullScreenAdCallbacks.onShowFailed`·`AdEvent.ShowFailed`·`FullScreenAdController.lastError` | 다음 휴지점에서 다시 시도한다. 컨트롤러가 다음 광고를 미리 불러 둔다 |
| `Configuration` | 설정이 모드와 맞지 않는다: 운영 모드의 빈 운영 ID(`CODE_PRODUCTION_ID_MISSING`)·시험 ID(`CODE_TEST_ID_IN_PRODUCTION`), 매니페스트 앱 ID 없음(`CODE_APP_ID_MISSING`, 두 모드 모두), 운영 모드의 시험 앱 ID(`CODE_TEST_APP_ID_IN_PRODUCTION`) | 던지지 않는다. `AdsClient.configurationErrors` 에 모이고(같은 원인은 한 번), 단위 문제는 `AdEvent.Misconfigured`, 앱 ID 문제는 `initializationError` 로도 온다 | 운영 빌드 설정을 고친다. 그동안 그 광고(앱 ID 면 전체)는 나가지 않는다 |

- 교체한 `FullScreenAdProvider` 가 `AdsException` 이 아닌 예외를 던져도 컨트롤러가 `Load`/`CODE_UNKNOWN` 으로 감싸 `cause` 를 보존한다(메인 스레드 크래시로 번지지 않는다)
- 코루틴 취소(`CancellationException`)는 감싸지 않고 전파한다
- 오류가 아닌 신호: 콜드 스타트에서 `gatherConsent` 를 부르기 전에는 지난 실행의 동의가 있어도 `consent.snapshot` 이 `Unknown`·`canRequestAds = false` 이고 `adsAvailable` 이 false 다(UMP 규칙). `gatherConsent` 가 갱신을 요청하는 즉시 저장된 동의가 반영된다
- 매니페스트 `APPLICATION_ID` meta-data 가 **아예 없으면** 광고 SDK 가 프로세스 시작 때 `IllegalStateException` 으로 앱을 멈추며 라이브러리까지 오지 않는다(README "앱이 해야 할 일"). 값이 비었거나 모드와 맞지 않으면 위 `Configuration` 이다
- **빈 광고 단위 ID 는 던지지 않는다.** 운영 모드에서 비었거나 시험 ID 면 그 광고만 숨기고 `Configuration` 으로 알린다 — 운영 앱을 설정 실수 하나로 멈추지 않으면서, 조용히 숨기지도 않는다(원인이 `configurationErrors`·이벤트·로그에 남는다). 개발 모드에서는 개발 ID(기본 시험 ID)를 쓰므로 운영 ID 가 비어도 정상이다
- `AdEventListener`·`AdFrequencyStore` 구현이 던진 예외는 삼키지 않고 호출 지점으로 전파한다

## IllegalArgumentException

| 조건 | 메시지 |
|---|---|
| `AdsConfig.testDeviceIds` 에 빈 문자열 | `testDeviceIds 에 빈 문자열이 있습니다: …` |
| `AdsConfig.appOpenCooldownMillis`·`appOpenMinIntervalMillis`·`appOpenSkipFirstLaunches` 음수 | `<이름> 는 0 이상이어야 합니다: …` |
| `gatherConsent` 의 `switchTimeoutMillis` 음수 | `switchTimeoutMillis 는 0 이상이어야 합니다: …` |
| `defaultPolicy` 에 전면형이 아닌 형식 | `전면형 형식이 아닙니다: …` |
| `IntervalFrequencyPolicy`·`LaunchCountFrequencyPolicy` 의 빈 `key`, `skipFirstLaunches` 음수 | `key 가 비었습니다` / `skipFirstLaunches 는 0 이상이어야 합니다: …` |
| `AdFrequencyStore.sharedPreferences` 의 빈 파일 이름 | `fileName 이 비었습니다` |
| `rememberNativeAdPool` 의 `refreshAfterMillis` < 1 | `refreshAfterMillis 는 1 이상이어야 합니다: …` |
| `IntervalFrequencyPolicy` 시간이 음수 / `maxShows` ≤ 0 | `minIntervalMillis`·`initialDelayMillis` 는 0 이상, `maxShows` 는 1 이상 |
| `PreloadPolicy` 값 범위 | `maxRetries`·`retryBackoffMillis`·`noFillBackoffMillis`·`loadTimeoutMillis` 0 이상, `expireAfterMillis` 1 이상 |
| `PreloadPolicy.nextStep` 의 `retriesDone` 음수 | `retriesDone 은 0 이상이어야 합니다: …` |
| `AdUnit` 의 빈 `name`·`development` | `name 이 비었습니다: format=…` / `development 가 비었습니다: name=…` |
| `AdUnit.idFor(AdsMode.Auto)` | `정하지 않은 모드입니다: Auto (AdsClient.mode 를 넘기세요)` |
| 형식이 맞지 않는 단위(배너 단위를 전면 컨트롤러에, 대체 단위의 형식이 다름 등) — 컨트롤러·`AdBanner`·풀·카드·종료 팝업이 받는 즉시 | `형식이 맞지 않는 광고 단위입니다: name=…, format=… (허용: …)` |
| `AdUnitRegistry` 이름 중복 / 없는 이름 `get` | `광고 단위 이름이 겹칩니다: …` / `등록되지 않은 광고 단위입니다: … (등록: …)` |
| `AdsEnabledSource.all()` 인자 없음 | `sources 가 비었습니다` |
| `prefetchedNativeAd` 의 `ttlMillis` < 1 | `ttlMillis 는 1 이상이어야 합니다: …` |
| 빈 `placement` | `placement 가 비었습니다: name=…` |
| `AdInterleave` 인자(`interval`·`firstAdAfter` < 1, `maxAds` < 0), `plan(음수)` | 받은 값 포함 |
| `InterleavePlan` 인덱스 범위 밖, 광고 자리/콘텐츠 자리 혼동 | 허용 범위 포함 |
| `rememberNativeAdPool` 풀 크기가 1..5 밖 | 받은 값 포함 |
| 네이티브 `media` 뷰가 `MediaView` 가 아님 | `media 뷰는 MediaView 여야 합니다: …` |

## IllegalStateException

- destroy 한 `FullScreenAdController.show` 호출: `destroy 된 컨트롤러입니다: <placement>`
- 미리보기가 아닌 곳에서 `LocalAdsClient` 없이 `AdBanner` 등을 호출: `LocalAdsClient 가 없습니다 — 화면 루트를 ProvideAdsClient 로 감싸세요(미리보기는 AdPreviewMode)`.
  Android Studio 프리뷰와 `AdPreviewMode` 안에서는 클라이언트 없이 자리표시를 그린다

## 값으로 알리는 신호

| 신호 | 의미 |
|---|---|
| `ShowResult.NotReady` | 보일 광고가 없다(불러오는 중·불러오기 실패). 불러오기를 시작해 둔다. 원인은 `lastError` |
| `ShowResult.Suppressed(Misconfigured)` | 단위가 모드와 맞지 않아 아무것도 불러오지 않는 컨트롤러다(`FullScreenAdController.misconfigured`) |
| `ShowResult.Suppressed(Unavailable)` | 스위치 꺼짐·동의 없음·초기화 전 |
| `ShowResult.Suppressed(FrequencyCapped)` | 빈도 정책이 막음. 앱 오프닝은 첫 실행, 다른 전면형이 닫힌 직후(`appOpenCooldownMillis`)에도 이 값 |
| `ShowResult.Suppressed(AnotherAdShowing)` | 이 클라이언트의 다른 전면형 광고가 떠 있다 |
| `ShowResult.Failed(error)` | 표시가 곧바로 실패했다(`kind = Show`, `reason` 으로 구분). 들고 있던 광고가 만료된 경우도 `Expired` 로 여기 온다. SDK 가 알린 실패면 `onShowFailed` 도 불렸다 |
| `AdsClient.warnings` | 운영 모드라 무시한 개발 전용 설정(`TestDeviceIdsIgnored`·`ConsentDebugGeographyIgnored`). 로그 경고도 남는다 |
| `NativeAdPool.state` / `AdSlotState` | `Idle`(쓸 수 없음)·`Loading`·`Loaded`·`Failed(error)` |
| `ConsentGatherResult.SkippedAdsDisabled` | 스위치가 꺼져 있어(스위치 Flow 실패 포함) 동의 폼을 띄우지 않았다 |
| `ConsentGatherResult.SkippedSwitchTimeout` | 스위치 첫 값이 제한 시간 안에 오지 않았다. 값이 온 뒤 다시 부른다 |
| `ConsentGatherResult.AlreadyInProgress` | 다른 동의 수집이 진행 중이다 |
| `NativeAdHandle.bind` 가 false | 정책상 이 레이아웃에 보일 수 없는 광고(미디어 뷰 없는 레이아웃의 동영상 광고). `NativeAdSlot` 은 그 광고를 그리지 않는다 |
| `ConsentStatus.Unknown` | 갱신 전이거나 SDK 가 새 상태 값을 냈다 — 광고를 요청하지 않는 쪽으로 본다 |

## 빌드 설정 때문에 나는 런타임 오류

| 증상 | 원인 | 처리 |
|---|---|---|
| release 앱이 시작 즉시 `RuntimeException: Unable to get provider androidx.startup.InitializationProvider` ← `Failed to create an instance of androidx.work.impl.WorkDatabase` | R8 이 광고 SDK 가 끌어오는 `WorkDatabase_Impl` 의 생성자를 지웠다 | 0.0.2 의 consumer 규칙이 막는다. 앱이 `consumerProguardFiles` 를 끄거나 규칙을 덮어쓰지 않았는지 확인한다 |
| logcat 에 WorkManager 의 `Could not create Input Merger` | 같은 이유로 `InputMerger` 생성자가 지워졌다 | 위와 같다 |

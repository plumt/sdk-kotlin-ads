# 오류와 실패 신호

이 라이브러리의 실패 타입은 `AdsException` 하나다(`kind`·`code`·`cause`). 틀린 인자는 `IllegalArgumentException`, 잘못된 상태는 `IllegalStateException` 이다.
메시지 문구는 바뀔 수 있으니 분기는 예외 타입과 `kind`·`code` 로 한다.

## AdsException

| kind | 언제 | 어디서 나오나 | 호출부가 할 일 |
|---|---|---|---|
| `Consent` | 동의 정보 갱신·동의 폼·개인정보 옵션 폼 실패(`code` 는 UMP `FormError.ErrorCode`) | `ConsentManager.gather`·`showPrivacyOptions`·`AdsClient.gatherConsent` 가 던진다 | 오프라인(`INTERNET_ERROR`)은 정상 상태로 보고 재시도한다. 지난 실행의 동의가 요청을 허용하면 광고는 계속 쓸 수 있다 |
| `Initialization` | SDK 초기화 실패. 대표 원인은 매니페스트 `APPLICATION_ID` 누락·오류 | 던지지 않고 `AdsClient.initializationError` 에 담긴다. 광고는 쓸 수 없는 상태로 남고 다음 동의·스위치 변화 때 재시도한다 | 앱 ID 를 확인한다 |
| `Load` | 광고 불러오기 실패, 배너 뷰 생성 실패. 채울 광고 없음(`code` 3)은 정상 운영 상태 | 컨트롤러·`AdBanner`·네이티브 풀이 `AdEvent.LoadFailed` 로 알린다(던지지 않음). `AdNetwork` 공급자는 던진다 | 분석 훅으로 집계한다. 컨트롤러는 `PreloadPolicy.maxRetries` 만큼 재시도한다 |
| `Show` | 광고 표시 실패 | `FullScreenAdCallbacks.onShowFailed`·`AdEvent.ShowFailed` | 다음 휴지점에서 다시 시도한다. 컨트롤러가 다음 광고를 미리 불러 둔다 |

- 교체한 `FullScreenAdProvider` 가 `AdsException` 이 아닌 예외를 던져도 컨트롤러가 `Load`/`CODE_UNKNOWN` 으로 감싸 `cause` 를 보존한다(메인 스레드 크래시로 번지지 않는다)
- 코루틴 취소(`CancellationException`)는 감싸지 않고 전파한다

## IllegalArgumentException

| 조건 | 메시지 |
|---|---|
| `AdsConfig.testDeviceIds` 에 빈 문자열 | `testDeviceIds 에 빈 문자열이 있습니다: …` |
| `IntervalFrequencyPolicy` 시간이 음수 / `maxShows` ≤ 0 | `minIntervalMillis`·`initialDelayMillis` 는 0 이상, `maxShows` 는 1 이상 |
| `PreloadPolicy` 값 범위 | `maxRetries`·`retryBackoffMillis` 0 이상, `expireAfterMillis` 1 이상 |
| `fullScreenController` 에 전면형이 아닌 형식 | `전면형 형식이 아닙니다: …` |
| 빈 `placement`·`adUnitId` | `placement 가 비었습니다` / `adUnitId 가 비었습니다: placement=…` |
| `AdInterleave` 인자(`interval`·`firstAdAfter` < 1, `maxAds` < 0), `plan(음수)` | 받은 값 포함 |
| `InterleavePlan` 인덱스 범위 밖, 광고 자리/콘텐츠 자리 혼동 | 허용 범위 포함 |
| `AdBanner`·`rememberNativeAdPool` 에 빈 단위 ID, 풀 크기가 1..5 밖 | 받은 값 포함 |
| 네이티브 `media` 뷰가 `MediaView` 가 아님 | `media 뷰는 MediaView 여야 합니다: …` |

## IllegalStateException

- destroy 한 `FullScreenAdController.show` 호출: `destroy 된 컨트롤러입니다: <placement>`
- `LocalAdsClient` 없이 `AdBanner` 등을 호출: `LocalAdsClient 가 없습니다 — 화면 루트를 ProvideAdsClient 로 감싸세요`

## 값으로 알리는 신호

| 신호 | 의미 |
|---|---|
| `ShowResult.NotReady` | 보일 광고가 없다(불러오는 중·실패·만료). 필요하면 불러오기를 시작해 둔다 |
| `ShowResult.Suppressed(Unavailable)` | 스위치 꺼짐·동의 없음·초기화 전 |
| `ShowResult.Suppressed(FrequencyCapped)` | 빈도 정책이 막음 |
| `ConsentStatus.Unknown` | 갱신 전이거나 SDK 가 새 상태 값을 냈다 — 광고를 요청하지 않는 쪽으로 본다 |

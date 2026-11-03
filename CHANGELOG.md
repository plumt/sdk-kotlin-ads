# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## ads

### [0.0.1] - 미발행

#### 추가
- `AdsClient` 진입점(`create`), `AdsConfig`, `AdAudience`/`AdTarget`
- 동의(UMP): `ConsentManager`, `ConsentSnapshot`, `ConsentStatus`
- `AdsEnabledSource`(광고 끄기 스위치), `MutableAdsEnabledSource`
- 전면·보상형·앱 오프닝: `FullScreenAdController`, `PreloadPolicy`, `AdFrequencyPolicy`, `IntervalFrequencyPolicy`, `AdClock`
- `AdEvent`/`AdEventListener` 분석 훅, `AdsException`
- 교체 가능한 `AdNetwork` 와 기본 구현 `MobileAdsNetwork`

## ads-compose

### [0.0.1] - 미발행

#### 추가
- `ProvideAdsClient`, `AdBanner`(앵커드 적응형), `rememberNativeAdPool`/`NativeAdSlot`
- `AdInterleave`/`InterleavePlan`/`itemsWithAds` 목록 광고 끼워넣기
- `rememberFullScreenAdController`

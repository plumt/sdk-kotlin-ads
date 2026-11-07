package net.plumt.ads

/** 동의(UMP) 흐름을 시험할 때 쓰는 가짜 지역. 운영 빌드에서는 [Disabled] 로 둔다. */
public enum class ConsentDebugGeography {
    /** 실제 지역을 쓴다. */
    Disabled,

    /** 유럽 경제 지역(EEA)인 것처럼 동작한다. */
    Eea,

    /** EEA 가 아닌 것처럼 동작한다(UMP 의 `DEBUG_GEOGRAPHY_OTHER`). */
    NotEea,

    /** 개인정보 규제가 있는 미국 주인 것처럼 동작한다. */
    RegulatedUsState,
}

/**
 * 광고 계층 설정. 광고 단위 ID 는 여기 두지 않는다 — 호출부가 화면·배치마다 [AdUnit] 으로 넘긴다.
 *
 * @property mode 개발·운영 모드. 기본 [AdsMode.Auto] 는 앱의 디버그 가능 여부로 정한다
 * @property audience 대상 연령 설정
 * @property testDeviceIds 시험 기기 ID. SDK 로그에 `Use RequestConfiguration.Builder.setTestDeviceIds` 로 찍히는 값.
 *   시험 기기에서는 실제 광고 단위도 시험 광고가 나온다. **개발 모드에서만 적용한다** — 운영 모드에서는 무시하고 [AdsWarning] 으로 알린다
 * @property consentDebugGeography 동의 폼 시험용 가짜 지역. [testDeviceIds] 에 든 기기에서만 효과가 있고, 개발 모드에서만 적용한다
 * @property appOpenCooldownMillis 다른 전면형 광고(전면·보상형)가 닫힌 뒤 앱 오프닝 광고를 막는 시간. 광고를 연달아 보이지 않게 한다
 * @property appOpenMinIntervalMillis 앱 오프닝 기본 정책의 최소 표시 간격
 * @property appOpenSkipFirstLaunches 앱 오프닝 기본 정책이 광고를 보이지 않는 처음 실행 수. 0 이면 첫 실행부터 보인다
 * @throws IllegalArgumentException [testDeviceIds] 에 빈 문자열이 있거나 시간·횟수 값이 음수일 때
 */
public data class AdsConfig(
    val mode: AdsMode = AdsMode.Auto,
    val audience: AdAudience = AdAudience.Default,
    val testDeviceIds: List<String> = emptyList(),
    val consentDebugGeography: ConsentDebugGeography = ConsentDebugGeography.Disabled,
    val appOpenCooldownMillis: Long = DEFAULT_APP_OPEN_COOLDOWN_MILLIS,
    val appOpenMinIntervalMillis: Long = DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS,
    val appOpenSkipFirstLaunches: Int = DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES,
) {
    init {
        require(testDeviceIds.none { it.isBlank() }) {
            "testDeviceIds 에 빈 문자열이 있습니다: $testDeviceIds"
        }
        require(appOpenCooldownMillis >= 0) { "appOpenCooldownMillis 는 0 이상이어야 합니다: $appOpenCooldownMillis" }
        require(appOpenMinIntervalMillis >= 0) { "appOpenMinIntervalMillis 는 0 이상이어야 합니다: $appOpenMinIntervalMillis" }
        require(appOpenSkipFirstLaunches >= 0) { "appOpenSkipFirstLaunches 는 0 이상이어야 합니다: $appOpenSkipFirstLaunches" }
    }

    public companion object {
        /** 기본 앱 오프닝 대기: 60초. */
        public const val DEFAULT_APP_OPEN_COOLDOWN_MILLIS: Long = 60_000L

        /** 기본 앱 오프닝 최소 간격: 2분. */
        public const val DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS: Long = 120_000L

        /** 기본으로 앱 오프닝을 보이지 않는 처음 실행 수: 1(첫 실행). */
        public const val DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES: Int = 1
    }
}

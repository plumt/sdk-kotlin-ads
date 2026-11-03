package net.plumt.ads

/** 동의(UMP) 흐름을 시험할 때 쓰는 가짜 지역. 운영 빌드에서는 [Disabled] 로 둔다. */
public enum class ConsentDebugGeography {
    /** 실제 지역을 쓴다. */
    Disabled,

    /** 유럽 경제 지역(EEA)인 것처럼 동작한다. */
    Eea,

    /** EEA 가 아닌 것처럼 동작한다. */
    NotEea,
}

/**
 * 광고 계층 설정. 광고 단위 ID 는 여기 두지 않는다 — 호출부가 화면·배치마다 넘긴다.
 *
 * @property audience 대상 연령 설정
 * @property testDeviceIds 시험 기기 ID. SDK 로그에 `Use RequestConfiguration.Builder.setTestDeviceIds` 로 찍히는 값.
 *   시험 기기에서는 실제 광고 단위도 시험 광고가 나온다. 운영 빌드에는 넣지 않는다
 * @property consentDebugGeography 동의 폼 시험용 가짜 지역. [testDeviceIds] 에 든 기기에서만 효과가 있다
 * @throws IllegalArgumentException [testDeviceIds] 에 빈 문자열이 있을 때
 */
public data class AdsConfig(
    val audience: AdAudience = AdAudience.Default,
    val testDeviceIds: List<String> = emptyList(),
    val consentDebugGeography: ConsentDebugGeography = ConsentDebugGeography.Disabled,
) {
    init {
        require(testDeviceIds.none { it.isBlank() }) {
            "testDeviceIds 에 빈 문자열이 있습니다: $testDeviceIds"
        }
    }
}

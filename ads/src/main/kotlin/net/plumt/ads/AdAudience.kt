package net.plumt.ads

/** 아동 대상 처리 같은 3치 설정. 기본은 [Unspecified] 로 SDK 기본 동작을 건드리지 않는다. */
public enum class AdFlag { Unspecified, Yes, No }

/** 광고 콘텐츠 등급 상한. SDK 의 최대 광고 콘텐츠 등급에 대응한다. */
public enum class AdContentRating { Unspecified, G, PG, T, MA }

/**
 * 앱의 대상 연령. 스토어에 신고한 타겟층과 맞춘다. [AdAudience.forTarget] 이 광고 설정으로 바꾼다.
 *
 * - [AllAges]: 13세 미만 포함(연령 확인 없음). 모든 사용자를 아동으로 취급한다
 * - [Teen]: 13세 이상
 * - [Adult]: 18세 이상만
 */
public enum class AdTarget { AllAges, Teen, Adult }

/**
 * 광고 대상 연령 설정. 기본은 모두 "지정 안 함".
 *
 * 값은 SDK 초기화 전에 적용되므로 초기화 뒤에는 바꿀 수 없다.
 *
 * @property childDirected 아동 대상 처리(COPPA) 태그
 * @property underAgeOfConsent 동의 연령 미만(GDPR) 태그. 동의 요청에도 같은 값이 쓰인다
 * @property maxContentRating 이 등급을 넘는 광고는 오지 않는다
 */
public data class AdAudience(
    val childDirected: AdFlag = AdFlag.Unspecified,
    val underAgeOfConsent: AdFlag = AdFlag.Unspecified,
    val maxContentRating: AdContentRating = AdContentRating.Unspecified,
) {
    public companion object {
        /** 아무것도 지정하지 않는다. */
        public val Default: AdAudience = AdAudience()

        /**
         * 타겟층을 광고 설정으로 바꾼다. 등급 상한은 연령의 기본값일 뿐이라, 앱 콘텐츠 등급이 더 낮으면
         * `copy(maxContentRating = …)` 로 내린다.
         *
         * [AdTarget.AllAges] 는 아동 대상 처리·동의 연령 미만·G 등급을 모두 켠다. 이 값을 쓰는 앱은 자기 매니페스트에서
         * 광고 ID 권한(`com.google.android.gms.permission.AD_ID`)을 `tools:node="remove"` 로 지운다 — 라이브러리가
         * 조건부로 뺄 수 없다.
         */
        public fun forTarget(target: AdTarget): AdAudience = when (target) {
            AdTarget.AllAges -> AdAudience(AdFlag.Yes, AdFlag.Yes, AdContentRating.G)
            AdTarget.Teen -> AdAudience(AdFlag.No, AdFlag.Unspecified, AdContentRating.T)
            AdTarget.Adult -> AdAudience(AdFlag.No, AdFlag.No, AdContentRating.MA)
        }
    }
}

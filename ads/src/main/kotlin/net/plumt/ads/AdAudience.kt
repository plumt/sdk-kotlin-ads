package net.plumt.ads

/** 아동 대상 처리 같은 3치 설정. 기본은 [Unspecified] 로 SDK 기본 동작을 건드리지 않는다. 닫힌 집합이다. */
public sealed interface AdFlag {
    /** 지정하지 않는다(SDK 기본). */
    public data object Unspecified : AdFlag

    /** 예. */
    public data object Yes : AdFlag

    /** 아니오. */
    public data object No : AdFlag
}

/** 광고 콘텐츠 등급 상한. SDK 의 최대 광고 콘텐츠 등급에 대응한다. 닫힌 집합이다. */
public sealed interface AdContentRating {
    /** 지정하지 않는다(SDK 기본). */
    public data object Unspecified : AdContentRating

    /** 모든 연령. */
    public data object G : AdContentRating

    /** 보호자 지도. */
    public data object PG : AdContentRating

    /** 청소년. */
    public data object T : AdContentRating

    /** 성인. */
    public data object MA : AdContentRating
}

/**
 * 앱의 대상 연령. 스토어에 신고한 타겟층과 맞춘다. [AdAudience.forTarget] 이 광고 설정으로 바꾼다. 닫힌 집합이다.
 */
public sealed interface AdTarget {
    /** 13세 미만 포함(연령 확인 없음). 모든 사용자를 아동으로 취급한다. */
    public data object AllAges : AdTarget

    /** 13세 이상. */
    public data object Teen : AdTarget

    /** 18세 이상만. */
    public data object Adult : AdTarget
}

/**
 * 광고 대상 연령 설정. 값은 SDK 초기화 전에 적용되므로 초기화 뒤에는 바꿀 수 없다.
 *
 * @property childDirected 아동 대상 처리(COPPA) 태그
 * @property underAgeOfConsent 동의 연령 미만(GDPR) 태그. 동의 요청에도 같은 값이 쓰인다
 * @property maxContentRating 이 등급을 넘는 광고는 오지 않는다
 */
public class AdAudience(
    public val childDirected: AdFlag,
    public val underAgeOfConsent: AdFlag,
    public val maxContentRating: AdContentRating,
) {
    /** 아무것도 지정하지 않는다([Default] 와 같다). */
    public constructor() : this(AdFlag.Unspecified, AdFlag.Unspecified, AdContentRating.Unspecified)

    /** 등급 상한만 바꾼 값. 연령 기본값보다 앱 콘텐츠 등급이 낮을 때 쓴다. */
    public fun withMaxContentRating(rating: AdContentRating): AdAudience = AdAudience(childDirected, underAgeOfConsent, rating)

    /** 아동 대상 처리 태그만 바꾼 값. */
    public fun withChildDirected(flag: AdFlag): AdAudience = AdAudience(flag, underAgeOfConsent, maxContentRating)

    /** 동의 연령 미만 태그만 바꾼 값. */
    public fun withUnderAgeOfConsent(flag: AdFlag): AdAudience = AdAudience(childDirected, flag, maxContentRating)

    override fun equals(other: Any?): Boolean =
        other is AdAudience && childDirected == other.childDirected && underAgeOfConsent == other.underAgeOfConsent &&
            maxContentRating == other.maxContentRating

    override fun hashCode(): Int = (childDirected.hashCode() * 31 + underAgeOfConsent.hashCode()) * 31 + maxContentRating.hashCode()

    override fun toString(): String =
        "AdAudience(childDirected=$childDirected, underAgeOfConsent=$underAgeOfConsent, maxContentRating=$maxContentRating)"

    public companion object {
        /** 아무것도 지정하지 않는다. */
        @JvmField public val Default: AdAudience = AdAudience()

        /**
         * 타겟층을 광고 설정으로 바꾼다. 등급 상한은 연령의 기본값일 뿐이라, 앱 콘텐츠 등급이 더 낮으면
         * [withMaxContentRating] 으로 내린다.
         *
         * [AdTarget.AllAges] 는 아동 대상 처리·동의 연령 미만·G 등급을 모두 켠다. 이 값을 쓰는 앱은 자기 매니페스트에서
         * 광고 ID 권한(`com.google.android.gms.permission.AD_ID`)을 `tools:node="remove"` 로 지운다 — 라이브러리가
         * 조건부로 뺄 수 없다.
         */
        @JvmStatic
        public fun forTarget(target: AdTarget): AdAudience = when (target) {
            AdTarget.AllAges -> AdAudience(AdFlag.Yes, AdFlag.Yes, AdContentRating.G)
            AdTarget.Teen -> AdAudience(AdFlag.No, AdFlag.Unspecified, AdContentRating.T)
            AdTarget.Adult -> AdAudience(AdFlag.No, AdFlag.No, AdContentRating.MA)
        }
    }
}

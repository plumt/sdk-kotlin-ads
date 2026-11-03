package net.plumt.ads

/** 광고 형식. */
public enum class AdFormat { Banner, Native, Interstitial, Rewarded, AppOpen }

/** 표시를 막은 이유. */
public enum class SuppressReason {
    /** [AdsEnabledSource] 가 꺼져 있거나 동의·초기화가 아직 광고 요청을 허용하지 않는다. */
    Unavailable,

    /** [AdFrequencyPolicy] 가 막았다. */
    FrequencyCapped,
}

/**
 * 분석 도구로 넘길 수 있는 광고 사건. 라이브러리는 분석 라이브러리에 의존하지 않고 [AdEventListener] 로만 알린다.
 * 광고 소재·사용자 식별 정보는 담지 않는다.
 */
public sealed class AdEvent {
    /** 광고 형식. */
    public abstract val format: AdFormat

    /** 호출부가 정한 배치 이름. */
    public abstract val placement: String

    public data class Requested(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class Loaded(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class LoadFailed(
        override val format: AdFormat,
        override val placement: String,
        val code: Int,
        val message: String,
    ) : AdEvent()

    public data class Impression(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class Clicked(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class Shown(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class ShowFailed(
        override val format: AdFormat,
        override val placement: String,
        val code: Int,
        val message: String,
    ) : AdEvent()

    public data class Dismissed(override val format: AdFormat, override val placement: String) : AdEvent()
    public data class RewardEarned(
        override val format: AdFormat,
        override val placement: String,
        val type: String,
        val amount: Int,
    ) : AdEvent()

    public data class Suppressed(
        override val format: AdFormat,
        override val placement: String,
        val reason: SuppressReason,
    ) : AdEvent()
}

/** [AdEvent] 를 받는 훅. 메인 스레드에서 불린다. 구현은 빨리 끝내고 예외를 던지지 않는다(던지면 라이브러리가 삼키지 않고 호출 지점으로 전파한다). */
public fun interface AdEventListener {
    public fun onEvent(event: AdEvent)

    public companion object {
        /** 아무것도 하지 않는다. */
        public val None: AdEventListener = AdEventListener { }

        /** 여러 리스너에 차례로 전달한다. */
        public fun combine(vararg listeners: AdEventListener): AdEventListener =
            AdEventListener { event -> listeners.forEach { it.onEvent(event) } }
    }
}

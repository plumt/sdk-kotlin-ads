package net.plumt.ads

/**
 * 광고 형식. 형식이 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다. 같은 형식은 늘 같은 인스턴스다.
 */
public class AdFormat private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    /** Google 공개 시험 단위 ID([TestAdIds]). */
    internal val testUnitId: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<AdFormat>()
        private fun of(name: String, testUnitId: String) = AdFormat(name, testUnitId).also { all += it }

        /** 배너. */
        @JvmField public val Banner: AdFormat = of("Banner", TestAdIds.BANNER)

        /** 네이티브. */
        @JvmField public val Native: AdFormat = of("Native", TestAdIds.NATIVE)

        /** 전면. */
        @JvmField public val Interstitial: AdFormat = of("Interstitial", TestAdIds.INTERSTITIAL)

        /** 보상형. */
        @JvmField public val Rewarded: AdFormat = of("Rewarded", TestAdIds.REWARDED)

        /** 앱 오프닝. */
        @JvmField public val AppOpen: AdFormat = of("AppOpen", TestAdIds.APP_OPEN)

        /** 정의된 모든 값(정의 순서). */
        public val values: List<AdFormat> get() = all.toList()
    }
}

/** 표시를 막은 이유. 이유가 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다. */
public class SuppressReason private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<SuppressReason>()
        private fun of(name: String) = SuppressReason(name).also { all += it }

        /** [AdsEnabledSource] 가 꺼져 있거나 동의·초기화가 아직 광고 요청을 허용하지 않는다. */
        @JvmField public val Unavailable: SuppressReason = of("Unavailable")

        /** [AdFrequencyPolicy] 가 막았다. 앱 오프닝은 다른 전면형 광고가 닫힌 직후([AdsConfig.appOpenCooldownMillis])에도 이 값이다. */
        @JvmField public val FrequencyCapped: SuppressReason = of("FrequencyCapped")

        /** 이 클라이언트의 다른 전면형 광고가 화면에 떠 있다. 전면형 광고는 한 번에 하나만 보인다. */
        @JvmField public val AnotherAdShowing: SuppressReason = of("AnotherAdShowing")

        /** 광고 단위 ID 가 모드와 맞지 않아(운영 모드의 빈 ID·시험 ID) 요청하지 않는다. [AdsClient.configurationErrors] 에 원인이 있다. */
        @JvmField public val Misconfigured: SuppressReason = of("Misconfigured")

        /** 정의된 모든 값(정의 순서). */
        public val values: List<SuppressReason> get() = all.toList()
    }
}

/**
 * 분석 도구로 넘길 수 있는 광고 사건. 라이브러리는 분석 라이브러리에 의존하지 않고 [AdEventListener] 로만 알린다.
 * 광고 소재·사용자 식별 정보는 담지 않는다. 닫힌 집합이라 `when` 에 `else` 가 필요 없다.
 */
public sealed interface AdEvent {
    /** 광고 형식. */
    public val format: AdFormat

    /** 호출부가 정한 배치 이름. */
    public val placement: String

    /** 광고를 요청했다. */
    public class Requested(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Requested && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Requested(format=$format, placement=$placement)"
    }

    /** 광고가 도착했다. */
    public class Loaded(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Loaded && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Loaded(format=$format, placement=$placement)"
    }

    /**
     * 불러오기가 실패했다.
     *
     * @property code 네트워크가 준 오류 코드(기록용)
     * @property reason 원인 분류. 분석·대시보드에서 이 값으로 묶는다
     */
    public class LoadFailed(
        override val format: AdFormat,
        override val placement: String,
        public val code: Int,
        public val message: String,
        public val reason: AdErrorReason,
    ) : AdEvent {
        override fun equals(other: Any?): Boolean =
            other is LoadFailed && sameSlot(other) && code == other.code && message == other.message && reason == other.reason
        override fun hashCode(): Int = ((slotHash() * 31 + code) * 31 + message.hashCode()) * 31 + reason.hashCode()
        override fun toString(): String =
            "LoadFailed(format=$format, placement=$placement, code=$code, message=$message, reason=$reason)"
    }

    /** 노출이 기록됐다. */
    public class Impression(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Impression && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Impression(format=$format, placement=$placement)"
    }

    /** 광고가 눌렸다. */
    public class Clicked(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Clicked && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Clicked(format=$format, placement=$placement)"
    }

    /** 전면형 광고가 화면에 올랐다. */
    public class Shown(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Shown && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Shown(format=$format, placement=$placement)"
    }

    /**
     * 전면형 광고 표시가 실패했다.
     *
     * @property code 네트워크가 준 오류 코드(기록용)
     * @property reason 원인 분류
     */
    public class ShowFailed(
        override val format: AdFormat,
        override val placement: String,
        public val code: Int,
        public val message: String,
        public val reason: AdErrorReason,
    ) : AdEvent {
        override fun equals(other: Any?): Boolean =
            other is ShowFailed && sameSlot(other) && code == other.code && message == other.message && reason == other.reason
        override fun hashCode(): Int = ((slotHash() * 31 + code) * 31 + message.hashCode()) * 31 + reason.hashCode()
        override fun toString(): String =
            "ShowFailed(format=$format, placement=$placement, code=$code, message=$message, reason=$reason)"
    }

    /** 전면형 광고가 닫혔다. */
    public class Dismissed(override val format: AdFormat, override val placement: String) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Dismissed && sameSlot(other)
        override fun hashCode(): Int = slotHash()
        override fun toString(): String = "Dismissed(format=$format, placement=$placement)"
    }

    /** 보상형 광고의 보상 조건을 채웠다. 화면이 재생성돼도 잃지 않도록 앱 수명의 리스너에서 처리한다. */
    public class RewardEarned(
        override val format: AdFormat,
        override val placement: String,
        public val type: String,
        public val amount: Int,
    ) : AdEvent {
        override fun equals(other: Any?): Boolean =
            other is RewardEarned && sameSlot(other) && type == other.type && amount == other.amount
        override fun hashCode(): Int = (slotHash() * 31 + type.hashCode()) * 31 + amount
        override fun toString(): String = "RewardEarned(format=$format, placement=$placement, type=$type, amount=$amount)"
    }

    /** 광고 단위 ID 가 모드와 맞지 않아 요청하지 않았다([AdsErrorKind.Configuration]). */
    public class Misconfigured(
        override val format: AdFormat,
        override val placement: String,
        public val code: Int,
        public val message: String,
    ) : AdEvent {
        override fun equals(other: Any?): Boolean =
            other is Misconfigured && sameSlot(other) && code == other.code && message == other.message
        override fun hashCode(): Int = (slotHash() * 31 + code) * 31 + message.hashCode()
        override fun toString(): String = "Misconfigured(format=$format, placement=$placement, code=$code, message=$message)"
    }

    /** 표시를 막았다. */
    public class Suppressed(
        override val format: AdFormat,
        override val placement: String,
        public val reason: SuppressReason,
    ) : AdEvent {
        override fun equals(other: Any?): Boolean = other is Suppressed && sameSlot(other) && reason == other.reason
        override fun hashCode(): Int = slotHash() * 31 + reason.hashCode()
        override fun toString(): String = "Suppressed(format=$format, placement=$placement, reason=$reason)"
    }
}

private fun AdEvent.sameSlot(other: AdEvent): Boolean = format == other.format && placement == other.placement

private fun AdEvent.slotHash(): Int = format.hashCode() * 31 + placement.hashCode()

/** [AdEvent] 를 받는 훅. 메인 스레드에서 불린다. 구현은 빨리 끝내고 예외를 던지지 않는다(던지면 라이브러리가 삼키지 않고 호출 지점으로 전파한다). */
public fun interface AdEventListener {
    /** 사건 하나. */
    public fun onEvent(event: AdEvent)

    public companion object {
        /** 아무것도 하지 않는다. */
        public val None: AdEventListener = AdEventListener { }

        /** 여러 리스너에 차례로 전달한다. */
        public fun combine(vararg listeners: AdEventListener): AdEventListener =
            AdEventListener { event -> listeners.forEach { it.onEvent(event) } }
    }
}

package net.plumt.ads

/**
 * [AdsException] 이 어느 단계에서 났는지. 분기는 메시지가 아니라 이 값으로 한다.
 * 단계가 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 */
public class AdsErrorKind private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<AdsErrorKind>()
        private fun of(name: String) = AdsErrorKind(name).also { all += it }

        /** 동의 정보 갱신·동의 폼·개인정보 옵션 폼 실패. */
        @JvmField public val Consent: AdsErrorKind = of("Consent")

        /** 광고 SDK 초기화 실패. */
        @JvmField public val Initialization: AdsErrorKind = of("Initialization")

        /** 광고 불러오기 실패(채울 광고 없음 포함). */
        @JvmField public val Load: AdsErrorKind = of("Load")

        /** 광고 표시 실패. */
        @JvmField public val Show: AdsErrorKind = of("Show")

        /** 앱이 꽂은 [AdsEnabledSource] 의 Flow 가 예외로 끝났다. 스위치는 꺼짐으로 본다. */
        @JvmField public val EnabledSource: AdsErrorKind = of("EnabledSource")

        /**
         * 설정이 모드와 맞지 않는다(운영 모드의 빈 운영 ID·시험 ID, 매니페스트 앱 ID 누락·시험 앱 ID).
         * 이 광고(앱 ID 면 SDK 전체)는 요청하지 않는다 — 운영 빌드가 시험 광고를 내보내지 않게 닫힌 쪽으로 실패한다.
         * [AdsClient.configurationErrors] 에 모인다.
         */
        @JvmField public val Configuration: AdsErrorKind = of("Configuration")

        /** 정의된 모든 값(정의 순서). */
        public val values: List<AdsErrorKind> get() = all.toList()
    }
}

/**
 * 실패의 원인 분류. 네트워크마다 다른 오류 코드를 이 값으로 옮긴다 — 재시도·화면 처리는 [AdsException.code] 가 아니라 이 값으로 정한다.
 * [retryable] 이 false 인 분류는 같은 요청을 다시 보내도 결과가 같다.
 * 분류가 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 */
public class AdErrorReason private constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    /** 같은 요청을 다시 보내 볼 만한가. */
    public val retryable: Boolean,
) {
    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<AdErrorReason>()
        private fun of(name: String, retryable: Boolean) = AdErrorReason(name, retryable).also { all += it }

        /** 채울 광고가 없다(중개의 채움 없음 포함). 정상 운영 상태다 — 길게 기다렸다 다시 시도한다. */
        @JvmField public val NoFill: AdErrorReason = of("NoFill", true)

        /** 네트워크 연결 실패. 연결이 돌아오면 다시 시도한다. */
        @JvmField public val Network: AdErrorReason = of("Network", true)

        /** 잘못된 요청(잘못된 단위 ID·광고 문자열 등). 다시 시도하지 않는다. */
        @JvmField public val InvalidRequest: AdErrorReason = of("InvalidRequest", false)

        /** 앱 ID 가 없다(매니페스트 또는 SDK 가 알린 누락). 다시 시도하지 않는다. */
        @JvmField public val AppIdMissing: AdErrorReason = of("AppIdMissing", false)

        /** 앱 ID 가 모드나 요청과 맞지 않는다(운영 모드의 시험 앱 ID, SDK 의 요청 ID 불일치). 다시 시도하지 않는다. */
        @JvmField public val AppIdMismatch: AdErrorReason = of("AppIdMismatch", false)

        /** 광고 단위 ID 가 모드와 맞지 않는다(운영 모드의 빈 ID·시험 ID). 요청하지 않는다. */
        @JvmField public val Misconfigured: AdErrorReason = of("Misconfigured", false)

        /** SDK 내부 오류. 보통 간격으로 다시 시도한다. */
        @JvmField public val Internal: AdErrorReason = of("Internal", true)

        /** 정해진 시간 안에 응답이 오지 않았다([PreloadPolicy.loadTimeoutMillis]). */
        @JvmField public val Timeout: AdErrorReason = of("Timeout", true)

        /** 표시: 이미 보인 광고를 다시 보이려 했다. 새 광고를 불러온다. */
        @JvmField public val AlreadyUsed: AdErrorReason = of("AlreadyUsed", true)

        /** 표시: 광고가 만료됐거나 준비되지 않았다. 새 광고를 불러온다. */
        @JvmField public val Expired: AdErrorReason = of("Expired", true)

        /** 표시: 앱이 전면에 있지 않다. 앱이 돌아온 뒤 다시 보인다. */
        @JvmField public val AppInBackground: AdErrorReason = of("AppInBackground", true)

        /** 분류할 수 없다. 보통 간격으로 다시 시도한다. */
        @JvmField public val Unknown: AdErrorReason = of("Unknown", true)

        /** 정의된 모든 값(정의 순서). */
        public val values: List<AdErrorReason> get() = all.toList()
    }
}

/**
 * 이 라이브러리의 실패 타입이다. 예상되는 실패는 결과 값([ShowResult.Failed]·[ConsentGatherResult.Failed] 등)이나
 * 상태 흐름에 담겨 오고, 던지는 곳은 교체 지점([net.plumt.ads.network.AdNetwork] 구현이 던지는 쪽)과 [AdUnit.idFor] 뿐이다.
 * 광고 SDK 가 낸 오류는 [code]·[cause] 에 담아 감싸고, 원인 분류는 [reason] 이다.
 *
 * @property kind 실패 단계
 * @property code SDK 가 준 오류 코드. 코드가 없는 실패는 [CODE_UNKNOWN]
 * @property reason 원인 분류
 */
public class AdsException(
    public val kind: AdsErrorKind,
    public val code: Int,
    message: String,
    cause: Throwable?,
    public val reason: AdErrorReason,
) : Exception("[$kind/$reason/$code] $message", cause) {
    private val detail: String = message

    /** 분류를 생략한다. 이 라이브러리의 설정 코드면 그에 맞는 분류, 나머지는 [AdErrorReason.Unknown]. */
    public constructor(kind: AdsErrorKind, code: Int, message: String) :
        this(kind, code, message, null, defaultReason(kind, code))

    /** 원인을 담고 분류를 생략한다. */
    public constructor(kind: AdsErrorKind, code: Int, message: String, cause: Throwable?) :
        this(kind, code, message, cause, defaultReason(kind, code))

    /** 단계·코드·메시지·원인은 그대로 두고 분류만 [reason] 으로 바꾼 예외. 같은 분류면 자신을 돌려준다. */
    public fun withReason(reason: AdErrorReason): AdsException =
        if (reason == this.reason) this else AdsException(kind, code, detail, cause, reason)

    public companion object {
        /** SDK 오류 코드가 없을 때. */
        public const val CODE_UNKNOWN: Int = -1

        /** [AdsErrorKind.Configuration]: 운영 모드인데 운영 광고 단위 ID 가 비었다. */
        public const val CODE_PRODUCTION_ID_MISSING: Int = -100

        /** [AdsErrorKind.Configuration]: 운영 모드인데 광고 단위 ID 가 Google 시험 ID 이거나 개발 ID 와 같다. */
        public const val CODE_TEST_ID_IN_PRODUCTION: Int = -101

        /** [AdsErrorKind.Configuration]: 매니페스트에 앱 ID(`com.google.android.gms.ads.APPLICATION_ID`)가 없거나 비었다. */
        public const val CODE_APP_ID_MISSING: Int = -102

        /** [AdsErrorKind.Configuration]: 운영 모드인데 매니페스트 앱 ID 가 Google 시험 앱 ID 다. */
        public const val CODE_TEST_APP_ID_IN_PRODUCTION: Int = -103

        /** [AdErrorReason.Timeout]: 불러오기가 [PreloadPolicy.loadTimeoutMillis] 안에 끝나지 않았다. */
        public const val CODE_TIMEOUT: Int = -110

        /** [AdErrorReason.Expired]: 불러온 광고가 [PreloadPolicy.expireAfterMillis] 를 넘겨 버렸다. */
        public const val CODE_EXPIRED: Int = -111

        internal fun defaultReason(kind: AdsErrorKind, code: Int): AdErrorReason = when (code) {
            CODE_PRODUCTION_ID_MISSING, CODE_TEST_ID_IN_PRODUCTION -> AdErrorReason.Misconfigured
            CODE_APP_ID_MISSING -> AdErrorReason.AppIdMissing
            CODE_TEST_APP_ID_IN_PRODUCTION -> AdErrorReason.AppIdMismatch
            CODE_TIMEOUT -> AdErrorReason.Timeout
            CODE_EXPIRED -> AdErrorReason.Expired
            else -> if (kind == AdsErrorKind.Configuration) AdErrorReason.Misconfigured else AdErrorReason.Unknown
        }
    }
}

/** 오프라인이면 [AdErrorReason.Network] 로 볼 분류 — SDK 가 연결 실패를 이렇게 알리기도 한다. */
private val OFFLINE_AMBIGUOUS = setOf(AdErrorReason.Internal, AdErrorReason.Timeout, AdErrorReason.Unknown)

/**
 * 연결이 끊긴 채([online] 이 false) 난 불러오기 실패를 [AdErrorReason.Network] 로 고친다.
 * 광고 SDK 는 오프라인을 내부 오류·모름으로 알리기도 해서, 그대로 두면 재시도가 연결 복구를 기다리지 않고 짧은 간격으로 되풀이된다.
 * 불러오기 실패가 아니거나, 연결돼 있거나, 분류가 내부·시간 초과·모름이 아니면 그대로 돌려준다.
 */
public fun AdsException.classifyOffline(online: Boolean): AdsException =
    if (!online && kind == AdsErrorKind.Load && reason in OFFLINE_AMBIGUOUS) withReason(AdErrorReason.Network) else this

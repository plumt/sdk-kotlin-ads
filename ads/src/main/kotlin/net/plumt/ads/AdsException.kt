package net.plumt.ads

/** [AdsException] 이 어느 단계에서 났는지. 분기는 메시지가 아니라 이 값으로 한다. */
public enum class AdsErrorKind {
    /** 동의 정보 갱신·동의 폼·개인정보 옵션 폼 실패. */
    Consent,

    /** 광고 SDK 초기화 실패. */
    Initialization,

    /** 광고 불러오기 실패(채울 광고 없음 포함). */
    Load,

    /** 광고 표시 실패. */
    Show,
}

/**
 * 이 라이브러리가 값 대신 던지거나 [Result] 에 담는 유일한 실패 타입이다.
 * 광고 SDK 가 낸 오류는 [code]·[cause] 에 담아 감싼다.
 *
 * @property kind 실패 단계
 * @property code SDK 가 준 오류 코드. 코드가 없는 실패는 [CODE_UNKNOWN]
 */
public class AdsException(
    public val kind: AdsErrorKind,
    public val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception("[$kind/$code] $message", cause) {
    public companion object {
        /** SDK 오류 코드가 없을 때. */
        public const val CODE_UNKNOWN: Int = -1
    }
}

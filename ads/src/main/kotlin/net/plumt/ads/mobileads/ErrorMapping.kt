package net.plumt.ads.mobileads

import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException

/**
 * 광고 SDK 불러오기 오류 코드(`LoadAdError.ErrorCode` 의 정수 값)의 분류. 모르는 코드는 [AdErrorReason.Unknown].
 * SDK 는 오프라인(호스트 이름 조회 실패·연결 거부)을 네트워크 코드가 아니라 내부 오류(0)로 알리기도 한다 —
 * 그때는 [message] 로 [AdErrorReason.Network] 를 가려낸다.
 */
internal fun loadReason(code: Int, message: String? = null): AdErrorReason {
    val byCode = when (code) {
        LoadAdError.ErrorCode.NO_FILL.value -> AdErrorReason.NoFill
        LoadAdError.ErrorCode.NETWORK_ERROR.value -> AdErrorReason.Network
        LoadAdError.ErrorCode.TIMEOUT.value -> AdErrorReason.Timeout
        LoadAdError.ErrorCode.INVALID_REQUEST.value, LoadAdError.ErrorCode.NOT_FOUND.value -> AdErrorReason.InvalidRequest
        LoadAdError.ErrorCode.APP_ID_MISSING.value -> AdErrorReason.AppIdMissing
        LoadAdError.ErrorCode.REQUEST_ID_MISMATCH.value -> AdErrorReason.AppIdMismatch
        LoadAdError.ErrorCode.INTERNAL_ERROR.value, LoadAdError.ErrorCode.INVALID_AD_RESPONSE.value -> AdErrorReason.Internal
        else -> AdErrorReason.Unknown
    }
    val ambiguous = byCode == AdErrorReason.Internal || byCode == AdErrorReason.Unknown
    return if (ambiguous && looksLikeNetworkFailure(message)) AdErrorReason.Network else byCode
}

/** 연결 실패를 뜻하는 SDK·플랫폼 메시지 조각(소문자). 기기 언어와 무관하게 영문으로 온다. */
private val NETWORK_MESSAGE_HINTS = listOf(
    "unable to resolve host",
    "unknownhostexception",
    "no address associated with hostname",
    "failed to connect",
    "connectexception",
    "network is unreachable",
    "sockettimeoutexception",
    "no network",
    "network error",
)

internal fun looksLikeNetworkFailure(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    return NETWORK_MESSAGE_HINTS.any { it in m }
}

/** 광고 SDK 표시 오류 코드(`FullScreenContentError.ErrorCode` 의 정수 값)의 분류. 모르는 코드는 [AdErrorReason.Unknown]. */
internal fun showReason(code: Int): AdErrorReason = when (code) {
    FullScreenContentError.ErrorCode.AD_REUSED.value -> AdErrorReason.AlreadyUsed
    FullScreenContentError.ErrorCode.H5_SHOW_AD_NOT_LOADED.value -> AdErrorReason.Expired
    FullScreenContentError.ErrorCode.APP_NOT_FOREGROUND.value -> AdErrorReason.AppInBackground
    FullScreenContentError.ErrorCode.INTERNAL_ERROR.value, FullScreenContentError.ErrorCode.MEDIATION_SHOW_ERROR.value ->
        AdErrorReason.Internal
    else -> AdErrorReason.Unknown
}

internal fun loadException(code: Int, message: String): AdsException =
    AdsException(AdsErrorKind.Load, code, message, null, loadReason(code, message))

internal fun showException(code: Int, message: String): AdsException =
    AdsException(AdsErrorKind.Show, code, message, null, showReason(code))

internal fun LoadAdError.toException(): AdsException = loadException(code.value, message)

internal fun FullScreenContentError.toException(): AdsException = showException(code.value, message)
